package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.membership.points.common.enums.BlacklistTypeEnum;
import com.membership.points.dto.request.BlacklistRequest;
import com.membership.points.entity.Blacklist;
import com.membership.points.mapper.BlacklistMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BlacklistService;
import com.membership.points.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 黑名单服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistServiceImpl implements BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;

    @Transactional
    @Override
    public void addToBlacklist(BlacklistRequest request) {
        Blacklist blacklist = new Blacklist();
        blacklist.setMemberId(request.getMemberId());
        blacklist.setBlockType(request.getBlockType());
        blacklist.setReason(request.getReason());
        blacklist.setOperator(request.getOperator());
        blacklist.setBlockedAt(LocalDateTime.now());
        blacklist.setActive(1);
        blacklist.setCreatedAt(LocalDateTime.now());
        blacklist.setUpdatedAt(LocalDateTime.now());

        blacklistMapper.insert(blacklist);

        cacheService.evictBlacklist(request.getMemberId());

        auditLogService.log("ADD_BLACKLIST", "BLACKLIST", blacklist.getId(),
                request.getMemberId(), request.getOperator(),
                "Added to blacklist: type=" + request.getBlockType() + ", reason=" + request.getReason());

        log.info("Member {} added to blacklist: type={}", request.getMemberId(), request.getBlockType());
    }

    @Transactional
    @Override
    public void removeFromBlacklist(Long memberId, String blockType, String operator) {
        LambdaUpdateWrapper<Blacklist> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Blacklist::getMemberId, memberId)
                .eq(Blacklist::getBlockType, blockType)
                .eq(Blacklist::getActive, 1)
                .set(Blacklist::getActive, 0)
                .set(Blacklist::getUnblockedAt, LocalDateTime.now())
                .set(Blacklist::getUpdatedAt, LocalDateTime.now());

        blacklistMapper.update(null, updateWrapper);

        cacheService.evictBlacklist(memberId);

        auditLogService.log("REMOVE_BLACKLIST", "BLACKLIST", null,
                memberId, operator,
                "Removed from blacklist: type=" + blockType);

        log.info("Member {} removed from blacklist: type={}", memberId, blockType);
    }

    @Override
    public boolean isBlocked(Long memberId, String blockType) {
        Set<String> cachedBlockTypes = cacheService.getBlacklist(memberId);

        if (cachedBlockTypes == null) {
            List<Blacklist> activeList = getActiveBlacklist(memberId);
            cachedBlockTypes = activeList.stream()
                    .map(Blacklist::getBlockType)
                    .collect(Collectors.toSet());
            cacheService.putBlacklist(memberId, cachedBlockTypes);
        }

        return cachedBlockTypes.contains(blockType)
                || cachedBlockTypes.contains(BlacklistTypeEnum.FULL_BLOCK.getCode());
    }

    @Override
    public boolean isBlockedForEarn(Long memberId) {
        Set<String> cachedBlockTypes = cacheService.getBlacklist(memberId);

        if (cachedBlockTypes == null) {
            List<Blacklist> activeList = getActiveBlacklist(memberId);
            cachedBlockTypes = activeList.stream()
                    .map(Blacklist::getBlockType)
                    .collect(Collectors.toSet());
            cacheService.putBlacklist(memberId, cachedBlockTypes);
        }

        return cachedBlockTypes.contains(BlacklistTypeEnum.EARN_BLOCK.getCode())
                || cachedBlockTypes.contains(BlacklistTypeEnum.FULL_BLOCK.getCode());
    }

    @Override
    public boolean isBlockedForRedeem(Long memberId) {
        Set<String> cachedBlockTypes = cacheService.getBlacklist(memberId);

        if (cachedBlockTypes == null) {
            List<Blacklist> activeList = getActiveBlacklist(memberId);
            cachedBlockTypes = activeList.stream()
                    .map(Blacklist::getBlockType)
                    .collect(Collectors.toSet());
            cacheService.putBlacklist(memberId, cachedBlockTypes);
        }

        return cachedBlockTypes.contains(BlacklistTypeEnum.REDEEM_BLOCK.getCode())
                || cachedBlockTypes.contains(BlacklistTypeEnum.FULL_BLOCK.getCode());
    }

    @Override
    public List<Blacklist> getActiveBlacklist(Long memberId) {
        LambdaQueryWrapper<Blacklist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Blacklist::getMemberId, memberId)
                .eq(Blacklist::getActive, 1);
        return blacklistMapper.selectList(wrapper);
    }
}
