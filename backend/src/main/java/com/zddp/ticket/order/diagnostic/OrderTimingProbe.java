package com.zddp.ticket.order.diagnostic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 下单耗时诊断探针：有界、线程安全汇总，默认关闭。
 *
 * <p>用途：区分「事务准备 / 库存更新 / 提交前后 / 连接等待」各占多少耗时，
 * 为下一步定位提供数据，而不是追求更高并发数字。只对<b>指定演出</b>的新建尝试计时，
 * 其他请求快速返回、不做任何采集。
 *
 * <p>工作方式：
 * <ul>
 *   <li>{@link OrderPlaceService} 在调用事务代理 {@code create} 之前调 {@link #begin(long)}，
 *       在代理返回/抛出后的 finally 里调 {@link #finish(boolean)}；</li>
 *   <li>{@link OrderCreateService} 在事务方法体内部沿调用顺序调 {@link #mark(Stage)}，
 *       记录同一样本的各个阶段点（同线程 ThreadLocal，不做跨线程传播）；</li>
 *   <li>样本保留<b>最多 {@value #MAX_SAMPLES} 条</b>，超出只累计丢弃计数，不无限缓存；
 *       应用正常关闭时（{@link PreDestroy}）一次性输出每阶段 count/avg/P95/max 及成功/失败/丢弃。</li>
 * </ul>
 *
 * <p>纪律：
 * <ul>
 *   <li>只在<b>外层代理返回</b>后记一次成功；内部方法正常返回但提交失败会以异常形式越过代理，
 *       因此成功 = 「代理调用未抛异常」；</li>
 *   <li>不在持锁阶段打印逐请求日志，无用户ID/密码/Cookie/SQL参数；</li>
 *   <li>失败/重试/复用旧订单不混入成功新建的平均值——阶段统计只来自成功样本，失败只累计计数；</li>
 *   <li>finally 不吞异常、不改变原异常，ThreadLocal 必须清理（防止线程池复用串数据）。</li>
 * </ul>
 */
@Component
public class OrderTimingProbe {

    private static final Logger log = LoggerFactory.getLogger(OrderTimingProbe.class);

    /** 每个阶段最多保留的样本数（成功样本）；超出只计丢弃，不无限缓存 */
    private static final int MAX_SAMPLES = 1000;

    /** 阶段点：事务方法内部沿业务先后顺序记录 */
    public enum Stage {
        /** create 方法体首行 */
        PROXY_ENTRY_END,
        /** 扣库存（reserveOne）调用前 */
        BODY_BEFORE_STOCK_END,
        /** 扣库存返回/抛出后 */
        STOCK_UPDATE_END,
        /** create 方法体 finally 结束 */
        BODY_END
    }

    /** 报告阶段：与定位口径一一对应，中文说明用于关闭时输出 */
    public enum Phase {
        TRANSACTION_TOTAL("transaction-total", "调用create前 → 代理返回/抛出（整个事务代理调用；不含入口原单查询与Web排队）"),
        PROXY_ENTRY("proxy-entry", "调用create前 → create方法体首行（事务开启/连接获取等，不能全叫连接等待）"),
        BODY_BEFORE_STOCK("body-before-stock", "方法体首行 → 扣库存前（读演出/查时间/插订单等准备成本）"),
        STOCK_UPDATE("stock-update", "扣库存调用前 → 返回/抛出（库存操作总耗时，含SQL/网络/可能的等锁，非纯锁等待）"),
        BODY_AFTER_STOCK("body-after-stock", "扣库存返回 → 方法体finally结束（锁后时间查询/校验/VO转换等）"),
        PROXY_EXIT("proxy-exit", "方法体finally结束 → create代理返回（含提交或回滚及事务清理，不能全叫commit）");

        private final String name;
        private final String chineseName;

        Phase(String name, String chineseName) {
            this.name = name;
            this.chineseName = chineseName;
        }

        public String getName() {
            return name;
        }

        public String getChineseName() {
            return chineseName;
        }
    }

    /** 单样本阶段耗时（纳秒）；某阶段缺边界时为 -1，表示该阶段未完整测量 */
    private static final class Sample {
        private final long beginNanos;
        private final long[] marks = new long[Stage.values().length];
        /** nanoTime 可为负数，独立标识阶段点是否已记录 */
        private final boolean[] recordedMarks = new boolean[Stage.values().length];
        private long finishNanos;
        private boolean success;

        Sample(long beginNanos) {
            this.beginNanos = beginNanos;
        }
    }

    /** 请求级样本：place 方法体入口 → finally 出口，内部只额外记录首次原单查询 */
    private static final class PlaceSample {
        private final long beginNanos;
        /** 首次原单查询；nanoTime 可为负，独立记录标志避免把 -1 误当有效阶段点 */
        private long activeQueryStart;
        private boolean activeQueryStartRecorded;
        private long activeQueryEnd;
        private boolean activeQueryEndRecorded;
        private long finishNanos;

        PlaceSample(long beginNanos) {
            this.beginNanos = beginNanos;
        }

        /** place-total：place 方法体入口至 finally 出口 */
        long placeTotal() {
            return finishNanos - beginNanos;
        }

        /** active-order-query：首次原单查询耗时；起点/终点任一未记录则未完整测量 */
        long activeOrderQuery() {
            return (activeQueryStartRecorded && activeQueryEndRecorded)
                    ? (activeQueryEnd - activeQueryStart)
                    : -1L;
        }
    }

    /** 某一阶段在保留样本上的统计 */
    public static final class Stats {
        /** 参与统计的成功样本数（该阶段边界完整的样本） */
        public final long count;
        /** 平均耗时（纳秒，四舍五入到整） */
        public final long avgNanos;
        /** P95 耗时（纳秒） */
        public final long p95Nanos;
        /** 最大耗时（纳秒） */
        public final long maxNanos;

        Stats(long count, long avgNanos, long p95Nanos, long maxNanos) {
            this.count = count;
            this.avgNanos = avgNanos;
            this.p95Nanos = p95Nanos;
            this.maxNanos = maxNanos;
        }
    }

    /** 汇总快照：成功/失败/丢弃计数 + 每阶段统计 */
    public static final class Summary {
        /** 成功新建（代理调用正常返回）的样本数 */
        public final long successCount;
        /** 失败（异常越过代理）的样本数 */
        public final long failedCount;
        /** 因样本数达到上限而未保留的样本数 */
        public final long droppedCount;
        /** 每阶段统计（仅来自成功样本） */
        public final Map<Phase, Stats> phasesByPhase;

        Summary(long successCount, long failedCount, long droppedCount, Map<Phase, Stats> phasesByPhase) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.droppedCount = droppedCount;
            this.phasesByPhase = phasesByPhase;
        }
    }

    /** 请求级汇总快照：成功新建请求 / 排除请求 / 丢弃 + place-total 与 active-order-query 统计 */
    public static final class PlaceSummary {
        /** 返回 created=true 的请求数（成功新建） */
        public final long successCount;
        /** 复用旧订单或抛异常的请求数；单独累计，不叫失败也不混入成功 */
        public final long excludedCount;
        /** 因样本数达到上限而未保留的请求样本数 */
        public final long droppedCount;
        /** place-total 统计（仅来自成功样本） */
        public final Stats placeTotal;
        /** active-order-query 统计（仅来自成功样本且查询边界完整） */
        public final Stats activeOrderQuery;

        PlaceSummary(long successCount, long excludedCount, long droppedCount,
                     Stats placeTotal, Stats activeOrderQuery) {
            this.successCount = successCount;
            this.excludedCount = excludedCount;
            this.droppedCount = droppedCount;
            this.placeTotal = placeTotal;
            this.activeOrderQuery = activeOrderQuery;
        }
    }

    private final boolean enabled;

    private final long performanceId;

    /** 纳秒时钟；默认用系统时钟，测试可注入可控时钟（用显式纳秒值，不 sleep） */
    private final LongSupplier nanoClock;

    /** 线程内当前样本；同线程同步调用，不做跨线程传播 */
    private final ThreadLocal<Sample> currentSample = new ThreadLocal<>();

    /** 线程内当前请求样本；与事务尝试级样本完全隔离，重试不重置请求起点 */
    private final ThreadLocal<PlaceSample> currentPlace = new ThreadLocal<>();

    /** 汇总状态；并发写入用同一把锁保护 */
    private final Object lock = new Object();
    private long successCount;
    private long failedCount;
    private long droppedCount;
    private final List<Sample> retainedSuccessSamples = new ArrayList<>();
    private long placeSuccessCount;
    private long placeExcludedCount;
    private long placeDroppedCount;
    private final List<PlaceSample> retainedPlaceSamples = new ArrayList<>();

    @Autowired
    public OrderTimingProbe(@Value("${ticket.order.diagnostic.enabled:false}") boolean enabled,
                            @Value("${ticket.order.diagnostic.performance-id:0}") long performanceId) {
        this(enabled, performanceId, System::nanoTime);
    }

    /** 仅测试用：注入可控纳秒时钟 */
    OrderTimingProbe(boolean enabled, long performanceId, LongSupplier nanoClock) {
        this.enabled = enabled;
        this.performanceId = performanceId;
        this.nanoClock = nanoClock;
    }

    /**
     * 开启一次「新建尝试」的计时。默认关闭或演出不匹配时直接返回，不产生任何样本。
     * 每次尝试独立计数；已由调用方保证 begin/finish 配对且在同一线程。
     */
    public void begin(long performanceId) {
        if (!enabled || performanceId != this.performanceId) {
            currentSample.remove();
            return;
        }
        currentSample.set(new Sample(nanoClock.getAsLong()));
    }

    /** 记录阶段点；当前线程没有活动样本（未开启/不匹配）时快速返回 */
    public void mark(Stage stage) {
        Sample sample = currentSample.get();
        if (sample == null) {
            return;
        }
        sample.marks[stage.ordinal()] = nanoClock.getAsLong();
        sample.recordedMarks[stage.ordinal()] = true;
    }

    /**
     * 结束当前样本并清理线程本地状态。
     *
     * <p>success 的语义：事务代理调用是否正常返回（代理调用在事务提交之后才返回，
     * 提交失败以异常形式越过代理）。内部方法正常返回但提交失败 → 传 {@code false}，归为失败。
     */
    public void finish(boolean success) {
        Sample sample = currentSample.get();
        currentSample.remove();
        if (sample == null) {
            return;
        }
        sample.finishNanos = nanoClock.getAsLong();
        sample.success = success;
        record(sample);
    }

    /**
     * 开启一次「下单请求」的计时（place 方法体入口）。默认关闭或演出不匹配时快速返回。
     *
     * <p>{@code performanceId} 可能为 null（参数校验会抛业务异常），此时不采集也不拆箱，
     * 不改变原参数校验异常。请求级样本与事务尝试级样本完全独立，重试不会重置请求起点。
     */
    public void beginPlace(Long performanceId) {
        if (!enabled || performanceId == null || performanceId != this.performanceId) {
            currentPlace.remove();
            return;
        }
        currentPlace.set(new PlaceSample(nanoClock.getAsLong()));
    }

    /** 首次原单查询调用前记录起点；无活动请求样本时快速返回 */
    public void markActiveQueryStart() {
        PlaceSample place = currentPlace.get();
        if (place == null) {
            return;
        }
        place.activeQueryStart = nanoClock.getAsLong();
        place.activeQueryStartRecorded = true;
    }

    /** 首次原单查询返回/抛出后记录终点；查询异常也在此清理请求级样本，不吞异常 */
    public void markActiveQueryEnd() {
        PlaceSample place = currentPlace.get();
        if (place == null) {
            return;
        }
        place.activeQueryEnd = nanoClock.getAsLong();
        place.activeQueryEndRecorded = true;
    }

    /**
     * 结束「下单请求」计时（place 方法体最外层 finally）并清理线程本地状态。
     *
     * <p>只在返回 {@code created=true} 时设为成功；复用旧订单或抛异常单独累计为排除请求
     * （不叫失败、也不混入成功新建的平均值）。重试只影响事务尝试级样本，place 最多记录一次。
     */
    public void finishPlace(boolean created) {
        PlaceSample place = currentPlace.get();
        currentPlace.remove();
        if (place == null) {
            return;
        }
        place.finishNanos = nanoClock.getAsLong();
        synchronized (lock) {
            if (created) {
                placeSuccessCount++;
                if (retainedPlaceSamples.size() < MAX_SAMPLES) {
                    retainedPlaceSamples.add(place);
                } else {
                    placeDroppedCount++;
                }
            } else {
                placeExcludedCount++;
            }
        }
    }

    /**
     * 请求级汇总快照（只读）。place-total 统计来自成功请求样本；
     * active-order-query 只统计查询边界完整的样本（查询异常视为未完整测量）。
     */
    public PlaceSummary placeSummary() {
        long success;
        long excluded;
        long dropped;
        List<PlaceSample> samples;
        synchronized (lock) {
            success = placeSuccessCount;
            excluded = placeExcludedCount;
            dropped = placeDroppedCount;
            samples = new ArrayList<>(retainedPlaceSamples);
        }
        long[] placeTotals = new long[samples.size()];
        long[] queryDurations = new long[samples.size()];
        int validPlaceTotals = 0;
        int validQueries = 0;
        for (PlaceSample p : samples) {
            long total = p.placeTotal();
            if (total >= 0) {
                placeTotals[validPlaceTotals++] = total;
            }
            long query = p.activeOrderQuery();
            if (query >= 0) {
                queryDurations[validQueries++] = query;
            }
        }
        return new PlaceSummary(success, excluded, dropped,
                validPlaceTotals == 0 ? new Stats(0, 0, 0, 0) : aggregate(Arrays.copyOf(placeTotals, validPlaceTotals)),
                validQueries == 0 ? new Stats(0, 0, 0, 0) : aggregate(Arrays.copyOf(queryDurations, validQueries)));
    }

    private void record(Sample sample) {
        synchronized (lock) {
            if (sample.success) {
                successCount++;
                if (retainedSuccessSamples.size() < MAX_SAMPLES) {
                    retainedSuccessSamples.add(sample);
                } else {
                    droppedCount++;
                }
            } else {
                // 失败只累计计数，不保留细节样本，避免混入成功阶段的平均值
                failedCount++;
            }
        }
    }

    /**
     * 汇总快照（只读，用于测试断言与关闭时输出）。
     *
     * <p>阶段统计只来自<b>成功新建</b>且该阶段边界完整的样本；失败不混入成功平均值。
     */
    public Summary summary() {
        long success;
        long failed;
        long dropped;
        List<Sample> samples;
        synchronized (lock) {
            success = successCount;
            failed = failedCount;
            dropped = droppedCount;
            samples = new ArrayList<>(retainedSuccessSamples);
        }
        Map<Phase, Stats> stats = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            long[] durations = new long[samples.size()];
            int valid = 0;
            for (Sample s : samples) {
                long d = durationOf(s, phase);
                if (d >= 0) {
                    durations[valid++] = d;
                }
            }
            stats.put(phase, valid == 0
                    ? new Stats(0, 0, 0, 0)
                    : aggregate(Arrays.copyOf(durations, valid)));
        }
        return new Summary(success, failed, dropped, stats);
    }

    private static long durationOf(Sample sample, Phase phase) {
        long[] d = durations(sample);
        return d[phase.ordinal()];
    }

    private static long[] durations(Sample s) {
        long[] d = new long[Phase.values().length];
        d[Phase.TRANSACTION_TOTAL.ordinal()] = pos(s.finishNanos - s.beginNanos);
        d[Phase.PROXY_ENTRY.ordinal()] = diff(s.beginNanos, true, s.marks[Stage.PROXY_ENTRY_END.ordinal()], s.recordedMarks[Stage.PROXY_ENTRY_END.ordinal()]);
        d[Phase.BODY_BEFORE_STOCK.ordinal()] = diff(s.marks[Stage.PROXY_ENTRY_END.ordinal()], s.recordedMarks[Stage.PROXY_ENTRY_END.ordinal()], s.marks[Stage.BODY_BEFORE_STOCK_END.ordinal()], s.recordedMarks[Stage.BODY_BEFORE_STOCK_END.ordinal()]);
        d[Phase.STOCK_UPDATE.ordinal()] = diff(s.marks[Stage.BODY_BEFORE_STOCK_END.ordinal()], s.recordedMarks[Stage.BODY_BEFORE_STOCK_END.ordinal()], s.marks[Stage.STOCK_UPDATE_END.ordinal()], s.recordedMarks[Stage.STOCK_UPDATE_END.ordinal()]);
        d[Phase.BODY_AFTER_STOCK.ordinal()] = diff(s.marks[Stage.STOCK_UPDATE_END.ordinal()], s.recordedMarks[Stage.STOCK_UPDATE_END.ordinal()], s.marks[Stage.BODY_END.ordinal()], s.recordedMarks[Stage.BODY_END.ordinal()]);
        d[Phase.PROXY_EXIT.ordinal()] = diff(s.marks[Stage.BODY_END.ordinal()], s.recordedMarks[Stage.BODY_END.ordinal()], s.finishNanos, true);
        return d;
    }

    /** 两个时间点都存在才取差值，否则该阶段未完整测量 */
    private static long diff(long from, boolean fromRecorded, long to, boolean toRecorded) {
        return (fromRecorded && toRecorded) ? (to - from) : -1L;
    }

    private static long pos(long v) {
        return v >= 0 ? v : -1L;
    }

    /** count/avg/P95/max；最多 MAX_SAMPLES 条，排序成本可忽略 */
    private static Stats aggregate(long[] durationNanos) {
        int n = durationNanos.length;
        long sum = 0;
        for (long v : durationNanos) {
            sum += v;
        }
        long[] sorted = durationNanos.clone();
        Arrays.sort(sorted);
        long p95 = sorted[(int) Math.ceil(n * 0.95) - 1];
        return new Stats(n, Math.round((double) sum / n), p95, sorted[n - 1]);
    }

    /** 应用正常关闭时一次性输出摘要；默认关闭或没有任何样本时不输出，避免噪声 */
    @PreDestroy
    public void logOnShutdown() {
        if (!enabled) {
            return;
        }
        Summary summary = summary();
        PlaceSummary place = placeSummary();
        if (summary.successCount == 0 && summary.failedCount == 0
                && place.successCount == 0 && place.excludedCount == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 下单耗时诊断摘要 ==========\n");
        sb.append(String.format("目标演出 performanceId=%d，单位：纳秒（nanoTime 差值，允许为负起点）。%n", performanceId));
        sb.append("—— 请求级（每次 place 请求，与事务尝试级独立）——\n");
        sb.append(String.format("成功新建=%d，排除(复用/抛异常)=%d，丢弃=%d%n",
                place.successCount, place.excludedCount, place.droppedCount));
        appendPhaseRow(sb, "place-total", place.placeTotal, "place方法体入口→finally出口（不含Web排队/序列化）");
        appendPhaseRow(sb, "active-order-query", place.activeOrderQuery, "首次selectActiveByUserAndPerformance调用前→返回（含取连接/SQL/映射）");
        sb.append("—— 事务尝试级（每次 create 事务代理调用）——\n");
        sb.append(String.format("成功=%d，失败=%d，丢弃=%d%n",
                summary.successCount, summary.failedCount, summary.droppedCount));
        sb.append("阶段统计仅来自【成功新建】样本（失败/排除不混入成功平均值）。\n");
        sb.append(String.format("%-18s %-8s %-12s %-12s %-12s %s%n",
                "阶段", "数量", "平均avg", "P95", "最大max", "中文说明"));
        for (Phase phase : Phase.values()) {
            Stats st = summary.phasesByPhase.get(phase);
            appendPhaseRow(sb, phase.getName(), st, phase.getChineseName());
        }
        sb.append("=======================================");
        log.info(sb.toString());
    }

    private static void appendPhaseRow(StringBuilder sb, String name, Stats st, String chineseName) {
        sb.append(String.format("%-18s %-8d %-12d %-12d %-12d %s%n",
                name, st.count, st.avgNanos, st.p95Nanos, st.maxNanos, chineseName));
    }
}
