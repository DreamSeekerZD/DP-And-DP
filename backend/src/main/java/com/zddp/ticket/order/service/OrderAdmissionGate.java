package com.zddp.ticket.order.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 下单准入闸（单实例内存态）。
 *
 * <p>Redis 预占数据异常、缓存与数据库不一致、Redis 连接故障等情况下，本闸<b>暂停</b>
 * 新下单准入，而 GET 订单、取消、模拟支付这类“已有订单”路径<b>不受影响</b>（它们不查本闸）。
 *
 * <p>两种暂停粒度，全部只在单实例内存中生效，没有持久化、没有公网管理 API：
 * <ul>
 *   <li><b>单场暂停</b>：某场演出 Redis 数据缺标记/不一致/损坏 → 暂停该场新下单，其他场正常；</li>
 *   <li><b>全局暂停</b>：Redis 连接异常或执行结果未知 → 暂停全部新下单。</li>
 * </ul>
 *
 * <p>恢复方式只能是<b>人工维护确认后重启应用</b>：内存暂停随进程退出清空；程序不承诺
 * 自动识别全部历史异常，也不在线自行解锁。这符合「重连不自动解禁、不建设持久控制平台」的约定。
 */
@Service
public class OrderAdmissionGate {

    /** 已暂停单场准入的演出编号 */
    private final Set<Long> pausedPerformances = new HashSet<Long>();

    /** 是否已全局暂停全部新下单 */
    private boolean globallyPaused = false;

    /** 该演出是否仍允许新下单（全局或单场任一被暂停即为关闭） */
    public synchronized boolean isAdmissionOpen(Long performanceId) {
        return !globallyPaused && (performanceId == null || !pausedPerformances.contains(performanceId));
    }

    /** 暂停单场：Redis 数据缺标记/损坏/不一致时调用；GET/支付/取消不受影响 */
    public synchronized void pausePerformance(Long performanceId) {
        if (performanceId != null) {
            pausedPerformances.add(performanceId);
        }
    }

    /** 全局暂停全部新下单：Redis 连接故障或执行结果未知时调用 */
    public synchronized void pauseGlobalAdmission() {
        globallyPaused = true;
    }

    /** 是否已全局暂停（供测试与维护核对） */
    public synchronized boolean isGloballyPaused() {
        return globallyPaused;
    }

    /** 当前单场暂停集合（防御性拷贝，供测试与维护核对） */
    public synchronized Set<Long> pausedPerformanceIds() {
        return Collections.unmodifiableSet(new HashSet<Long>(pausedPerformances));
    }
}