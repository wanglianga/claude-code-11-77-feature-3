package com.example.clearing.service;

import com.example.clearing.domain.ClearingTask;
import com.example.clearing.domain.Decision;
import com.example.clearing.domain.PublicationStatus;
import com.example.clearing.domain.PublishType;
import com.example.clearing.domain.ResultPublication;
import com.example.clearing.repo.ResultPublicationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果发布册：对外清算结果只追加、不改写。
 *  - 原始结果（ORIGINAL，revisionSeq=0）随回执确认发布，金额与规则版本冻结；
 *  - 更正结果（CORRECTION）发布时，原行转 SUPERSEDED 但原样保留，形成发布版本链；
 *  - 已回传结果没有任何更新接口，静默覆盖在治理服务层被拒绝并记录 RESULT_FROZEN_REJECTED。
 */
@Service
public class ResultPublicationService {

    private final ResultPublicationRepository publicationRepository;
    private final EventLog eventLog;

    public ResultPublicationService(ResultPublicationRepository publicationRepository,
            EventLog eventLog) {
        this.publicationRepository = publicationRepository;
        this.eventLog = eventLog;
    }

    /** 原始结果发布（幂等：同一任务只发布一次） */
    @Transactional
    public Optional<ResultPublication> publishOriginal(ClearingTask task, BigDecimal amount) {
        boolean exists = publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(
                task.getBusinessKey()).stream().anyMatch(p -> p.getTaskId().equals(task.getId()));
        if (exists) {
            return Optional.empty();
        }
        ResultPublication row = new ResultPublication();
        row.setBusinessKey(task.getBusinessKey());
        row.setRevisionSeq(0);
        row.setPublishType(PublishType.ORIGINAL);
        row.setStatus(PublicationStatus.PUBLISHED);
        row.setTaskId(task.getId());
        row.setBillVersion(task.getBillVersion());
        row.setRuleVersion(task.getRuleVersion());
        row.setAmount(amount);
        row.setClearingDate(task.getClearingDate());
        row.setNote("原始清算结果发布（规则版本 " + task.getRuleVersion() + "），金额冻结不可改写");
        ResultPublication saved = publicationRepository.save(row);
        eventLog.record(task, Decision.RESULT_PUBLISHED,
                "结果发布册：原始结果已发布（seq=0，规则 " + task.getRuleVersion()
                        + "，金额 " + amount + "），已回传结果不可静默覆盖");
        return Optional.of(saved);
    }

    /** 更正结果发布：上一发布行转 SUPERSEDED，追加 CORRECTION 行 */
    @Transactional
    public ResultPublication publishCorrection(ClearingTask correction, BigDecimal amount,
            Long diffCaseId, String note) {
        List<ResultPublication> trail =
                publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(correction.getBusinessKey());
        if (trail.isEmpty()) {
            throw new IllegalStateException("业务键 " + correction.getBusinessKey()
                    + " 尚无原始发布行，不能直接发布更正");
        }
        ResultPublication latest = trail.get(trail.size() - 1);
        latest.setStatus(PublicationStatus.SUPERSEDED);
        publicationRepository.save(latest);

        ResultPublication row = new ResultPublication();
        row.setBusinessKey(correction.getBusinessKey());
        row.setRevisionSeq(latest.getRevisionSeq() + 1);
        row.setPublishType(PublishType.CORRECTION);
        row.setStatus(PublicationStatus.PUBLISHED);
        row.setTaskId(correction.getId());
        row.setBillVersion(correction.getBillVersion());
        row.setRuleVersion(correction.getRuleVersion());
        row.setAmount(amount);
        LocalDate correctionDate = correction.getCorrectionDate() != null
                ? correction.getCorrectionDate() : correction.getClearingDate();
        row.setClearingDate(correctionDate);
        row.setSupersedesId(latest.getId());
        row.setDiffCaseId(diffCaseId);
        row.setNote(note);
        ResultPublication saved = publicationRepository.save(row);
        eventLog.record(correction, Decision.RESULT_REVISION_PUBLISHED,
                "结果发布册：更正结果已发布（seq=" + saved.getRevisionSeq()
                        + "，规则 " + correction.getRuleVersion() + "，补正日期 " + correctionDate
                        + "，金额 " + amount + "）；原发布行 " + latest.getId()
                        + " 标记 SUPERSEDED 但金额原样保留");
        return saved;
    }

    public List<ResultPublication> trail(String businessKey) {
        return publicationRepository.findByBusinessKeyOrderByRevisionSeqAsc(businessKey);
    }

    public Optional<ResultPublication> latest(String businessKey) {
        return publicationRepository.findFirstByBusinessKeyOrderByRevisionSeqDesc(businessKey);
    }

    public List<ResultPublication> all() {
        return publicationRepository.findAll();
    }

    /** 该业务键是否已有冻结的已发布结果 */
    public boolean hasPublished(String businessKey) {
        return publicationRepository.countByBusinessKey(businessKey) > 0;
    }

    public Instant frozenAt(String businessKey) {
        return latest(businessKey).map(ResultPublication::getPublishedAt).orElse(null);
    }
}
