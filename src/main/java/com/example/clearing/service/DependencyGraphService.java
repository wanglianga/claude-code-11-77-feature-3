package com.example.clearing.service;

import com.example.clearing.domain.DepHealth;
import com.example.clearing.domain.DependencyEdge;
import com.example.clearing.domain.DependencyNode;
import com.example.clearing.repo.DependencyEdgeRepository;
import com.example.clearing.repo.DependencyNodeRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖状态图：维护目录、待遇规则、基金网关、来源批次节点及其恢复条件；
 * 健康度变化联动本地令牌桶的冻结/解冻。
 */
@Service
public class DependencyGraphService {

    private final DependencyNodeRepository nodeRepository;
    private final DependencyEdgeRepository edgeRepository;
    private final TokenBucketService bucketService;

    public DependencyGraphService(DependencyNodeRepository nodeRepository,
            DependencyEdgeRepository edgeRepository,
            TokenBucketService bucketService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.bucketService = bucketService;
    }

    public List<DependencyNode> nodes() {
        return nodeRepository.findAll();
    }

    public List<DependencyEdge> edges() {
        return edgeRepository.findAll();
    }

    public DependencyNode node(String code) {
        return nodeRepository.findById(code).orElse(null);
    }

    public boolean isDown(String code) {
        return nodeRepository.findById(code)
                .map(n -> n.getHealth() == DepHealth.DOWN)
                .orElse(false);
    }

    /**
     * 更新依赖健康度。
     * DOWN → 冻结该依赖本地令牌桶（停止放行、停止补充）；
     * HEALTHY → 解冻（不补发冻结期间的尝试）；
     * DEGRADED → 不冻结，由协调器在配额证据中降额。
     */
    @Transactional
    public DependencyNode updateHealth(String code, DepHealth health, String reason) {
        DependencyNode node = nodeRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("未知依赖: " + code));
        node.setHealth(health);
        node.setUpdatedAt(Instant.now());
        nodeRepository.save(node);
        if (health == DepHealth.DOWN) {
            bucketService.freeze(code);
        } else if (health == DepHealth.HEALTHY) {
            bucketService.unfreeze(code);
        }
        return node;
    }

    @Transactional
    public void resetHealth() {
        for (DependencyNode node : nodeRepository.findAll()) {
            node.setHealth(DepHealth.HEALTHY);
            node.setUpdatedAt(Instant.now());
            nodeRepository.save(node);
        }
    }
}
