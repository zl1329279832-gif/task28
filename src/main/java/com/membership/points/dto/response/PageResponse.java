package com.membership.points.dto.response;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

@Data
public class PageResponse<T> {

    private List<T> records;

    private long total;

    private int pageNum;

    private int pageSize;

    private int totalPages;

    public static <T> PageResponse<T> from(IPage<T> page) {
        PageResponse<T> response = new PageResponse<>();
        response.setRecords(page.getRecords());
        response.setTotal(page.getTotal());
        response.setPageNum((int) page.getCurrent());
        response.setPageSize((int) page.getSize());
        response.setTotalPages((int) Math.ceil((double) page.getTotal() / page.getSize()));
        return response;
    }
}
