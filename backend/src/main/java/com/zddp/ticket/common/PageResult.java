package com.zddp.ticket.common;

import java.util.List;

/**
 * 分页结果，列表接口共用。
 *
 * <p>page 从 1 开始，pageSize 默认 20、上限 100；排序规则由各查询接口自行约定，
 * 当前演出列表统一为 createdAt DESC, id DESC。
 *
 * <p>total 刻意使用原始 long 而不是包装 Long：本项目把所有包装 Long 当作 BIGINT 编号
 * 并序列化成十进制字符串（见 JacksonConfig），计数必须保持 JSON 数字，不能被误转成字符串。
 *
 * @param <T> 列表元素类型
 */
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> items;

    /** 满足条件的总记录数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
