package com.zddp.ticket.performance.service;

import com.zddp.ticket.common.BusinessException;
import com.zddp.ticket.common.PageResult;
import com.zddp.ticket.performance.convert.PerformanceConvert;
import com.zddp.ticket.performance.enums.PerformanceStatus;
import com.zddp.ticket.performance.mapper.PerformanceMapper;
import com.zddp.ticket.performance.model.entity.Performance;
import com.zddp.ticket.performance.model.dto.SavePerformanceDTO;
import com.zddp.ticket.performance.model.vo.PerformanceVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 演出业务服务：草稿、编辑、发布、下架与查询。
 *
 * <p>三条贯穿全类的规则：
 * <ol>
 *   <li><b>归属</b>：运营只能操作自己的演出。归属不符一律按 404「不可见」处理，
 *       而不是 403，避免通过响应差异探测别人的资源是否存在。角色不符（普通用户访问运营接口）
 *       由 SecurityConfig 拦成 403。</li>
 *   <li><b>行锁 + 短事务</b>：编辑、发布、下架都在一个短事务里先按 id + publisherId
 *       {@code SELECT ... FOR UPDATE} 锁住本人这一行，拿到锁之后再判断状态和时间。
 *       这样「编辑」与「发布」并发时，后拿到锁的一方看到的一定是对方提交后的结果，
 *       不可能出现「先发布、后把关键字段改掉」。</li>
 *   <li><b>时间以数据库为准</b>：所有「当前时间」都来自 {@code UTC_TIMESTAMP(3)}，
 *       不用应用机器时间，避免多机时钟不一致与 JVM 默认时区干扰。</li>
 * </ol>
 *
 * <p>本阶段没有订单与库存扣减，因此这里不实现 reserveOne/releaseOne 之类的空方法。
 */
@Service
public class PerformanceService {

    /** 标题最大长度 */
    private static final int MAX_TITLE_LENGTH = 100;

    /** 介绍最大长度 */
    private static final int MAX_DESCRIPTION_LENGTH = 10000;

    /** 封面地址最大长度 */
    private static final int MAX_COVER_URL_LENGTH = 1024;

    /** 地点最大长度 */
    private static final int MAX_VENUE_LENGTH = 200;

    /** 票价下限与上限，单位：分 */
    private static final int MIN_PRICE_CENT = 1;
    private static final int MAX_PRICE_CENT = 100000000;

    /** 总库存下限与上限 */
    private static final int MIN_TOTAL_STOCK = 1;
    private static final int MAX_TOTAL_STOCK = 1000000;

    /** 每页条数上限 */
    private static final int MAX_PAGE_SIZE = 100;

    private final PerformanceMapper performanceMapper;

    private final PerformanceConvert performanceConvert;

    public PerformanceService(PerformanceMapper performanceMapper, PerformanceConvert performanceConvert) {
        this.performanceMapper = performanceMapper;
        this.performanceConvert = performanceConvert;
    }

    // ================================================================= 写操作

    /**
     * 新建草稿。
     *
     * <p>草稿允许字段不完整（空对象也能保存），但一旦提供了非空值就必须合法。
     * 可售库存由总库存派生，不接受前端指定；状态固定为草稿，发布时间固定为空。
     */
    @Transactional
    public PerformanceVO createDraft(Long publisherId, SavePerformanceDTO request) {
        SavePerformanceDTO normalized = prepareAndValidateInput(request);
        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();

        Performance performance = new Performance();
        applyEditableFields(performance, normalized);
        // 草稿阶段可售库存与总库存保持一致：总库存为空时可售也为空
        performance.setAvailableStock(normalized.getTotalStock());
        performance.setStatus(PerformanceStatus.DRAFT);
        performance.setPublisherId(publisherId);
        performance.setPublishedAt(null);
        performance.setCreatedAt(now);
        performance.setUpdatedAt(now);

        performanceMapper.insertPerformance(performance);
        return toResponse(performance, now);
    }

    /**
     * 编辑演出，PUT 语义：整体替换可编辑字段。
     *
     * <p>按状态分两条路径：
     * <ul>
     *   <li>草稿：可改全部可编辑字段；</li>
     *   <li>已发布/已下架：只允许改介绍与封面，关键字段必须原样带回，否则整笔拒绝（409）。</li>
     * </ul>
     */
    @Transactional
    public PerformanceVO update(Long publisherId, Long id, SavePerformanceDTO request) {
        requirePositiveId(id);
        SavePerformanceDTO normalized = prepareAndValidateInput(request);

        Performance current = lockOwned(id, publisherId);
        // 取锁之后的时间：等待锁的过程中数据库时间可能已经推进
        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();

        if (current.getStatus() == PerformanceStatus.DRAFT) {
            updateDraft(current, normalized, now);
        } else {
            updateDisplayFieldsOnly(current, normalized, now);
        }
        return toResponse(current, now);
    }

    /**
     * 发布：仅草稿可发布。
     *
     * <p>重复发布返回当前状态，不重置发布时间、不改库存；已下架禁止重新发布。
     * 完整性校验在拿到行锁之后、用数据库当前时间进行。
     */
    @Transactional
    public PerformanceVO publish(Long publisherId, Long id) {
        requirePositiveId(id);
        Performance current = lockOwned(id, publisherId);

        if (current.getStatus() == PerformanceStatus.PUBLISHED) {
            // 重复发布：直接返回当前状态。这里不执行任何 UPDATE，
            // 因此发布时间与库存不可能被重置。
            return toResponse(current, performanceMapper.selectDatabaseNowUtc());
        }
        if (current.getStatus() == PerformanceStatus.WITHDRAWN) {
            throw BusinessException.stateConflict("已下架的演出不能重新发布");
        }

        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();
        assertPublishable(current, now);

        int affected = performanceMapper.publishDraft(id, publisherId, now);
        if (affected != 1) {
            // 已持有行锁，理论上不会失败；失败说明状态被外部改动，整笔回滚
            throw BusinessException.stateConflict("演出状态已变化，请刷新后重试");
        }
        current.setStatus(PerformanceStatus.PUBLISHED);
        current.setPublishedAt(now);
        current.setUpdatedAt(now);
        return toResponse(current, now);
    }

    /**
     * 下架：仅已发布可下架。重复下架幂等，草稿下架返回 409。
     * 下架不回到草稿、不释放库存、不影响已有订单（本阶段还没有订单）。
     */
    @Transactional
    public PerformanceVO withdraw(Long publisherId, Long id) {
        requirePositiveId(id);
        Performance current = lockOwned(id, publisherId);
        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();

        if (current.getStatus() == PerformanceStatus.WITHDRAWN) {
            // 重复下架：不再改变任何字段
            return toResponse(current, now);
        }
        if (current.getStatus() == PerformanceStatus.DRAFT) {
            throw BusinessException.stateConflict("草稿不能下架");
        }

        int affected = performanceMapper.withdrawPublished(id, publisherId, now);
        if (affected != 1) {
            throw BusinessException.stateConflict("演出状态已变化，请刷新后重试");
        }
        current.setStatus(PerformanceStatus.WITHDRAWN);
        current.setUpdatedAt(now);
        return toResponse(current, now);
    }

    // ================================================================= 查询

    /** 公开列表：只含已发布内容；已开始、售罄仍然展示，只是不可购买 */
    public PageResult<PerformanceVO> queryPublic(int page, int pageSize) {
        validatePaging(page, pageSize);
        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();
        long total = performanceMapper.countPublic();
        List<Performance> rows = performanceMapper.selectPublicPage(pagingOffset(page, pageSize), pageSize);
        return new PageResult<PerformanceVO>(toResponses(rows, now), total, page, pageSize);
    }

    /** 公开详情：草稿与已下架按不存在处理 */
    public PerformanceVO detailPublic(Long id) {
        requirePositiveId(id);
        Performance performance = performanceMapper.selectPublicDetail(id);
        if (performance == null) {
            throw BusinessException.notFound("演出不存在或尚未发布");
        }
        return toResponse(performance, performanceMapper.selectDatabaseNowUtc());
    }

    /** 运营列表：只看得到本人内容，可按状态筛选（未知状态值 400） */
    public PageResult<PerformanceVO> queryOwned(Long publisherId, String statusFilter, int page, int pageSize) {
        validatePaging(page, pageSize);
        Integer statusCode = parseStatusFilter(statusFilter);
        LocalDateTime now = performanceMapper.selectDatabaseNowUtc();
        long total = performanceMapper.countOwned(publisherId, statusCode);
        List<Performance> rows = performanceMapper.selectOwnedPage(
                publisherId, statusCode, pagingOffset(page, pageSize), pageSize);
        return new PageResult<PerformanceVO>(toResponses(rows, now), total, page, pageSize);
    }

    /** 运营详情：本人所有状态都可见，他人的按不可见返回 404 */
    public PerformanceVO detailOwned(Long publisherId, Long id) {
        requirePositiveId(id);
        Performance performance = performanceMapper.selectOwnedDetail(id, publisherId);
        if (performance == null) {
            throw BusinessException.notFound("演出不存在或不属于当前运营人员");
        }
        return toResponse(performance, performanceMapper.selectDatabaseNowUtc());
    }

    // ================================================================= 内部实现

    /**
     * 锁定本人演出。返回 null 统一转成 404：既覆盖「编号不存在」，也覆盖「属于别人」。
     */
    private Performance lockOwned(Long id, Long publisherId) {
        Performance current = performanceMapper.selectOwnedForUpdate(id, publisherId);
        if (current == null) {
            throw BusinessException.notFound("演出不存在或不属于当前运营人员");
        }
        return current;
    }

    private void updateDraft(Performance current, SavePerformanceDTO request, LocalDateTime now) {
        applyEditableFields(current, request);
        // 草稿阶段可售库存始终与总库存一致；本阶段没有下单，不存在已售出的占用
        current.setAvailableStock(request.getTotalStock());
        current.setUpdatedAt(now);

        int affected = performanceMapper.updateDraft(current);
        if (affected != 1) {
            throw BusinessException.stateConflict("演出状态已变化，请刷新后重试");
        }
    }

    private void updateDisplayFieldsOnly(Performance current, SavePerformanceDTO request, LocalDateTime now) {
        assertKeyFieldsUnchanged(current, request);
        if (request.getDescription() == null) {
            // 介绍属于可改字段，但不能被改成空——已发布演出的展示信息不能残缺
            throw BusinessException.validation("已发布或已下架的演出必须保留介绍内容");
        }

        int affected = performanceMapper.updateDisplayFields(
                current.getId(), current.getPublisherId(), request.getDescription(), request.getCoverUrl(), now);
        if (affected != 1) {
            throw BusinessException.stateConflict("演出状态已变化，请刷新后重试");
        }
        current.setDescription(request.getDescription());
        current.setCoverUrl(request.getCoverUrl());
        current.setUpdatedAt(now);
    }

    /**
     * 已发布/已下架的关键字段必须与现值一致，否则整笔拒绝。
     *
     * <p>开始时间按<b>实际时刻</b>比较：请求里写成 +08:00 还是 Z，只要指向同一时刻就不算修改。
     * 比较前双方都已折算成 UTC 毫秒精度，与数据库列精度一致。
     */
    private void assertKeyFieldsUnchanged(Performance current, SavePerformanceDTO request) {
        boolean changed = !Objects.equals(current.getTitle(), request.getTitle())
                || !Objects.equals(current.getVenue(), request.getVenue())
                || !Objects.equals(current.getPriceCent(), request.getPriceCent())
                || !Objects.equals(current.getTotalStock(), request.getTotalStock())
                || !Objects.equals(current.getStartsAt(), toUtc(request.getStartsAt()));
        if (changed) {
            throw BusinessException.fieldsLocked(
                    "已发布或已下架的演出不允许修改标题、地点、开始时间、价格和总库存");
        }
    }

    /**
     * 发布前的完整性校验。
     * 「开始时间是否在未来」用数据库当前时间判断，时间相等即视为已到开始时间。
     */
    private void assertPublishable(Performance performance, LocalDateTime serverTimeUtc) {
        List<String> missing = new ArrayList<String>();
        if (performance.getTitle() == null) {
            missing.add("标题");
        }
        if (performance.getDescription() == null) {
            missing.add("介绍");
        }
        if (performance.getVenue() == null) {
            missing.add("地点");
        }
        if (performance.getStartsAt() == null) {
            missing.add("开始时间");
        }
        if (performance.getPriceCent() == null) {
            missing.add("票价");
        }
        if (performance.getTotalStock() == null) {
            missing.add("总库存");
        }
        if (performance.getAvailableStock() == null) {
            missing.add("可售库存");
        }
        if (!missing.isEmpty()) {
            throw BusinessException.notReady("发布前必须补齐：" + String.join("、", missing));
        }
        if (!performance.getStartsAt().isAfter(serverTimeUtc)) {
            throw BusinessException.notReady("开始时间必须晚于当前时间");
        }
    }

    private void applyEditableFields(Performance performance, SavePerformanceDTO request) {
        performance.setTitle(request.getTitle());
        performance.setDescription(request.getDescription());
        performance.setCoverUrl(request.getCoverUrl());
        performance.setVenue(request.getVenue());
        performance.setStartsAt(toUtc(request.getStartsAt()));
        performance.setPriceCent(request.getPriceCent());
        performance.setTotalStock(request.getTotalStock());
    }

    /**
     * 把实体转成对外响应，并补上需要实时计算的字段。
     * 注意 serverTime 用的是调用方取到的数据库时间，保证同一响应内所有判断基于同一时刻。
     */
    private PerformanceVO toResponse(Performance performance, LocalDateTime serverTimeUtc) {
        PerformanceVO response = performanceConvert.toResponse(performance);
        response.setServerTime(serverTimeUtc.atOffset(ZoneOffset.UTC));

        // 判断顺序固定：未发布 -> 已开始 -> 售罄。只表达演出自身可售性，
        // 不判断当前用户是否已购买（本阶段没有购买功能）。
        String reason = null;
        if (performance.getStatus() != PerformanceStatus.PUBLISHED) {
            reason = "NOT_PUBLISHED";
        } else if (performance.getStartsAt() != null && !performance.getStartsAt().isAfter(serverTimeUtc)) {
            reason = "STARTED";
        } else if (performance.getAvailableStock() != null && performance.getAvailableStock() <= 0) {
            reason = "SOLD_OUT";
        }
        response.setCanPurchase(reason == null);
        response.setUnavailableReason(reason);
        return response;
    }

    private List<PerformanceVO> toResponses(List<Performance> rows, LocalDateTime serverTimeUtc) {
        List<PerformanceVO> items = new ArrayList<PerformanceVO>(rows.size());
        for (Performance performance : rows) {
            items.add(toResponse(performance, serverTimeUtc));
        }
        return items;
    }

    // ================================================================= 校验

    /**
     * 整理并校验输入，返回一个整理后的 DTO 副本，<b>不修改传入的 DTO</b>。
     *
     * <p>文本字段的具体处理是：<b>去除首尾空白；空白字符串转 null</b>——
     * 也就是把 {@code "   "} 当成「没填」，而不是保存成空字符串。
     * 只动首尾，介绍内部的空格与换行原样保留，也不做全局字符串清洗。
     *
     * <p>整理之后再校验：一旦提供了非空但非法的值，即使是草稿也直接拒绝。
     */
    private SavePerformanceDTO prepareAndValidateInput(SavePerformanceDTO input) {
        SavePerformanceDTO prepared = new SavePerformanceDTO();
        prepared.setTitle(trimToNullIfBlank(input.getTitle()));
        prepared.setDescription(trimToNullIfBlank(input.getDescription()));
        prepared.setCoverUrl(trimToNullIfBlank(input.getCoverUrl()));
        prepared.setVenue(trimToNullIfBlank(input.getVenue()));
        // 时间与数值字段不做文本处理：时间由 toUtc 单独换算，数值在绑定阶段就要求是整数
        prepared.setStartsAt(input.getStartsAt());
        prepared.setPriceCent(input.getPriceCent());
        prepared.setTotalStock(input.getTotalStock());

        if (prepared.getTitle() != null && prepared.getTitle().length() > MAX_TITLE_LENGTH) {
            throw BusinessException.validation("标题最长 " + MAX_TITLE_LENGTH + " 字符");
        }
        if (prepared.getDescription() != null && prepared.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw BusinessException.validation("介绍最长 " + MAX_DESCRIPTION_LENGTH + " 字符");
        }
        if (prepared.getVenue() != null && prepared.getVenue().length() > MAX_VENUE_LENGTH) {
            throw BusinessException.validation("地点最长 " + MAX_VENUE_LENGTH + " 字符");
        }
        validateCoverUrl(prepared.getCoverUrl());
        validateRange("票价", prepared.getPriceCent(), MIN_PRICE_CENT, MAX_PRICE_CENT);
        validateRange("总库存", prepared.getTotalStock(), MIN_TOTAL_STOCK, MAX_TOTAL_STOCK);
        return prepared;
    }

    /**
     * 封面地址只接受 http/https。
     * 明确拒绝 javascript:、data: 这类协议：前端会把这个地址直接塞进 img 的 src，
     * 放行等于把协议注入的入口留在数据里。后端不下载该地址。
     */
    private void validateCoverUrl(String coverUrl) {
        if (coverUrl == null) {
            return;
        }
        if (coverUrl.length() > MAX_COVER_URL_LENGTH) {
            throw BusinessException.validation("封面地址最长 " + MAX_COVER_URL_LENGTH + " 字符");
        }
        String scheme;
        try {
            scheme = new URI(coverUrl).getScheme();
        } catch (URISyntaxException ex) {
            throw BusinessException.validation("封面地址不是合法的 URL");
        }
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw BusinessException.validation("封面地址仅支持 http 或 https");
        }
    }

    private void validateRange(String fieldName, Integer value, int min, int max) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw BusinessException.validation(fieldName + "必须在 " + min + " 到 " + max + " 之间");
        }
    }

    /**
     * 计算分页偏移。
     *
     * <p>先把 page 转成 long 再相乘：page 与 pageSize 都是 int，
     * 若先按 int 相乘，(page - 1) * pageSize 在极大页码下会溢出成负数，
     * 传给数据库就是一个负的 OFFSET。两个列表共用这一个算法，保持一致。
     * 这里不额外设置页码上限——越界页码是合法请求，返回空列表即可。
     */
    private long pagingOffset(int page, int pageSize) {
        return ((long) page - 1) * pageSize;
    }

    private void validatePaging(int page, int pageSize) {
        if (page < 1) {
            throw BusinessException.validation("page 必须大于等于 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BusinessException.validation("pageSize 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.validation("演出编号必须是正整数");
        }
    }

    private Integer parseStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.trim().isEmpty()) {
            return null;
        }
        String normalized = statusFilter.trim().toUpperCase(Locale.ROOT);
        for (PerformanceStatus status : PerformanceStatus.values()) {
            if (status.name().equals(normalized)) {
                return status.getCode();
            }
        }
        throw BusinessException.validation("未知的演出状态筛选值：" + statusFilter);
    }

    /**
     * 去除首尾空白；如果去除后是空字符串，就转成 null。
     * 只处理首尾，字符串内部的空格与换行原样保留。
     */
    private String trimToNullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 请求里带偏移的时间 -> 数据库里的 UTC 时间。
     *
     * <p>先按同一时刻折算到 UTC（不是丢掉偏移），再截断到毫秒，
     * 与数据库列 DATETIME(3) 的精度一致。
     * 这样「写进去再读出来」与原始时刻完全相同，编辑时按实际时刻比较关键字段也不会误判。
     */
    private LocalDateTime toUtc(OffsetDateTime value) {
        return value == null
                ? null
                : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
}
