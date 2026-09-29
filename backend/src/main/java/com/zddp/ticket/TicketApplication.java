package com.zddp.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 有限库存演出票务后端启动类。
 *
 * <p>业务根包 com.zddp.ticket，按 user / performance 分模块；当前 B01 阶段没有 order 模块，
 * 也不包含 Redis、MQ、定时任务或状态机平台。
 */
@SpringBootApplication
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
