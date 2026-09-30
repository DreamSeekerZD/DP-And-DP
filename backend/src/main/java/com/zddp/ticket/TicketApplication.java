package com.zddp.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 有限库存演出票务后端启动类。
 *
 * <p>业务根包 com.zddp.ticket，按 user / performance / order 分模块。
 * 没有 Redis、MQ 或状态机平台；唯一的定时任务是单实例的订单超时补扫
 * （{@code OrderTimeoutJob}），用 Spring 自带的调度即可，不引入分布式调度。
 */
@SpringBootApplication
@EnableScheduling
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
