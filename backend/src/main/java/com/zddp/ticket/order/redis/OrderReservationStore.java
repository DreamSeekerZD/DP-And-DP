package com.zddp.ticket.order.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * B05 Redis 原子预占/释放的适配器（两 key 收敛版）。只操作 Redis，不查 MySQL。
 *
 * <p>每场演出只用两类 key：
 * <ul>
 *   <li>{@code ticket:stock:<id>}：String 可售库存；key 存在且为严格非负十进制整数
 *       （0..2147483647）即视为“已就绪”；</li>
 *   <li>{@code ticket:occupied:<id>}：ZSet，member=userId，score=首次预占毫秒时间；
 *       重复请求不刷新 score；空集合在 Redis 中不存在是正常情况。</li>
 * </ul>
 * 不使用 owner Hash，也没有 reservationId 身份参数——占用身份的精确匹配不在本组件职责内。
 *
 * <p><b>释放重放边界（重要）</b>：release 只按“该用户是否在 occupied 集合中”判断，
 * 因此仅能防“没有再次占用时的重复释放”（成员不存在 → NOOP）；<b>不保证“重购后的旧释放”
 * 安全</b>——重购会重新登记同一 userId，若旧路径在重购后再释放一次，会误删新占用并错误归还库存。
 * B 段必须保证：仅在数据库真实关闭成功且提交后调用一次 release，且不会在重购建立后再按旧路径
 * 第二次释放。不要宣称本组件天然幂等。
 *
 * <p>所有校验都在 Lua 写操作之前完成：Lua 原子执行不等于运行错误自动回滚，
 * 因此类型/值格式/时间/越界全部前置。Redis 不可达等连接类异常（DataAccessException
 * 子类）原样向外抛，由 B 段调用方映射为 503，本组件不吞异常。
 */
@Service
public class OrderReservationStore {

    /** Lua 脚本类路径，与 src/main/resources/redis/ 下的脚本一一对应 */
    private static final String RESERVE_SCRIPT_PATH = "redis/order-reserve.lua";
    private static final String RELEASE_SCRIPT_PATH = "redis/order-release.lua";

    /** reserve Lua 返回码 */
    private static final long CODE_RESERVED = 0;
    private static final long CODE_OCCUPIED = 1;
    private static final long CODE_SOLD_OUT = 2;
    private static final long CODE_NOT_READY = 3;

    /** release Lua 返回码 */
    private static final long CODE_RELEASED = 0;
    private static final long CODE_RELEASE_NOOP = 1;
    private static final long CODE_RELEASE_CORRUPT = 2;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;

    @Autowired
    public OrderReservationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.reserveScript = loadScript(RESERVE_SCRIPT_PATH);
        this.releaseScript = loadScript(RELEASE_SCRIPT_PATH);
    }

    private static DefaultRedisScript<Long> loadScript(String scriptPath) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(scriptPath)));
        script.setResultType(Long.class);
        return script;
    }

    // ---------- key 命名（与 Lua 脚本中的 key 构造保持同一个契约，测试夹具也复用） ----------

    public static String stockKey(long performanceId) {
        return "ticket:stock:" + performanceId;
    }

    public static String occupiedKey(long performanceId) {
        return "ticket:occupied:" + performanceId;
    }

    // ---------- 预占 ----------

    /**
     * 原子预占一份库存并登记占用。
     *
     * <p>顺序遵循 B05 两 key 合同：先校验 key 类型、库存字符串格式与时间值，
     * 再判占用（OCCUPIED 优先于 SOLD_OUT），最后判库存；无库存时不写任何用户记录。
     */
    public OrderReservationResult reserve(long performanceId, long userId, long nowMillis) {
        List<String> keys = Arrays.asList(
                stockKey(performanceId), occupiedKey(performanceId));
        Long code = redisTemplate.execute(
                reserveScript, keys, String.valueOf(userId), String.valueOf(nowMillis));
        if (code == null) {
            // Lua 返回空说明脚本执行层面出了问题（例如运行期错误），不能当作任何业务结果
            throw new OrderReservationException("预占脚本无返回结果: performanceId=" + performanceId
                    + ", userId=" + userId + "，请核对 redis/order-reserve.lua 与 Redis 版本");
        }
        return mapReserveCode(performanceId, userId, code);
    }

    // ---------- 释放 ----------

    /**
     * 归还占用：仅当该用户在 occupied 集合中（ZREM 实际移除 1 个成员）才库存 +1；
     * 成员不存在 → NOOP。缺库存/非法值/错误类型/加 1 越界抛
     * {@link OrderReservationException}，绝不无条件归还。
     *
     * <p>见类注释：不保证“重购后的旧释放”安全，B 段必须约束调用路径只释放真实关闭的占用。
     */
    public OrderReleaseResult release(long performanceId, long userId) {
        List<String> keys = Arrays.asList(
                stockKey(performanceId), occupiedKey(performanceId));
        Long code = redisTemplate.execute(releaseScript, keys, String.valueOf(userId));
        if (code == null) {
            throw new OrderReservationException("释放脚本无返回结果: performanceId=" + performanceId
                    + ", userId=" + userId + "，请核对 redis/order-release.lua 与 Redis 版本");
        }
        return mapReleaseCode(performanceId, userId, code);
    }

    // ---------- 返回码映射 ----------

    private OrderReservationResult mapReserveCode(long performanceId, long userId, long code) {
        if (code == CODE_RESERVED) {
            return OrderReservationResult.RESERVED;
        }
        if (code == CODE_OCCUPIED) {
            return OrderReservationResult.OCCUPIED;
        }
        if (code == CODE_SOLD_OUT) {
            return OrderReservationResult.SOLD_OUT;
        }
        if (code == CODE_NOT_READY) {
            return OrderReservationResult.NOT_READY;
        }
        throw new OrderReservationException("预占脚本返回未知结果码: " + code
                + "（performanceId=" + performanceId + ", userId=" + userId + "）");
    }

    private OrderReleaseResult mapReleaseCode(long performanceId, long userId, long code) {
        if (code == CODE_RELEASED) {
            return OrderReleaseResult.RELEASED;
        }
        if (code == CODE_RELEASE_NOOP) {
            return OrderReleaseResult.NOOP;
        }
        if (code == CODE_RELEASE_CORRUPT) {
            // 缺库存/非法值/错误类型/越界等损坏场景：不能无条件归还，必须人工维护确认后才恢复准入
            throw new OrderReservationException("Redis 占用数据异常或库存缺失，需人工维护确认后才能恢复预占"
                    + "（performanceId=" + performanceId + ", userId=" + userId + "）");
        }
        throw new OrderReservationException("释放脚本返回未知结果码: " + code
                + "（performanceId=" + performanceId + ", userId=" + userId + "）");
    }
}