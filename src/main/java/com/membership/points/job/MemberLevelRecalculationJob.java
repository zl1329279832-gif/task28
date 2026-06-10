package com.membership.points.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.membership.points.entity.Member;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.service.MemberLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会员等级重算定时任务
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MemberLevelRecalculationJob {

    private final MemberLevelService memberLevelService;
    private final MemberMapper memberMapper;

    @Scheduled(cron = "${points.level-recalc.cron:0 0 3 * * SUN}")
    public void recalculateLevels() {
        log.info("会员等级重算任务开始执行");
        long start = System.currentTimeMillis();
        try {
            int page = 1;
            int size = 500;
            int total = 0;
            Page<Member> memberPage;
            do {
                memberPage = memberMapper.selectPage(new Page<>(page, size),
                        new LambdaQueryWrapper<Member>().eq(Member::getStatus, 1));
                for (Member member : memberPage.getRecords()) {
                    try {
                        memberLevelService.recalculateLevel(member.getId());
                        total++;
                    } catch (Exception e) {
                        log.error("会员{}等级重算失败", member.getId(), e);
                    }
                }
                page++;
            } while (memberPage.getRecords().size() == size);
            log.info("等级重算完成，处理{}个会员，耗时{}ms", total, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("等级重算任务异常", e);
        }
    }
}
