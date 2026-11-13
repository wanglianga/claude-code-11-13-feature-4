package com.example.rtbf.web;

import com.example.rtbf.service.RestoreReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 备份恢复后的删除复查：恢复先入隔离区，按当前有效删除凭证逐条比对，
 * 全部位置通过检查并登记血缘后才允许开放（详见 RestoreReviewService 约束）。
 */
@Tag(name = "备份恢复删除复查（隔离检查后开放）")
@RestController
@RequestMapping("/api/restore")
public class RestoreReviewController {

    private final RestoreReviewService restore;

    public RestoreReviewController(RestoreReviewService restore) {
        this.restore = restore;
    }

    public record RegisterBatchRequest(
            @NotNull LocalDate backupDate,
            String sourceRef,
            List<String> involvedTables,
            List<String> fileDirectories,
            List<String> manifestFiles) {
    }

    public record ManualDecisionRequest(@NotBlank String decision, @NotBlank String note) {
    }

    public record LinkageRequest(@NotNull Map<String, String> linkage, String assetCode) {
    }

    public record DiffResolveRequest(@NotBlank String note) {
    }

    public record OpenRequest(@NotBlank String basis) {
    }

    public record OpenDecisionRequest(@NotBlank String note) {
    }

    @Operation(summary = "可恢复备份来源目录（含是否具备主体关联字段）")
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        return restore.listCatalog();
    }

    @Operation(summary = "管理员登记恢复批次（备份日期、涉及表、文件目录、备份清单）")
    @PostMapping("/batches")
    public Map<String, Object> register(@Valid @RequestBody RegisterBatchRequest req, Principal principal) {
        return restore.registerBatch(new RestoreReviewService.RegisterBatchRequest(
            req.backupDate(), req.sourceRef(), req.involvedTables(),
            req.fileDirectories(), req.manifestFiles()), principal);
    }

    @Operation(summary = "恢复批次列表")
    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return restore.listBatches();
    }

    @Operation(summary = "批次详情：位置、复查项、文件差异、开放申请、附在原工单的残留记录")
    @GetMapping("/batches/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return restore.getBatch(id);
    }

    @Operation(summary = "恢复内容装载进隔离区（不触碰线上 biz 表）")
    @PostMapping("/batches/{id}/restore")
    public Map<String, Object> restoreLoad(@PathVariable long id, Principal principal) {
        return restore.restoreToQuarantine(id, principal);
    }

    @Operation(summary = "执行隔离复查扫描（幂等可重试：只处理未完成位置，动态追加新办结注销）")
    @PostMapping("/batches/{id}/scan")
    public Map<String, Object> scan(@PathVariable long id, Principal principal) {
        return restore.runScan(id, principal);
    }

    @Operation(summary = "重新扫描文件目录，自动关闭已补齐的缺件差异")
    @PostMapping("/batches/{id}/recheck-files")
    public Map<String, Object> recheckFiles(@PathVariable long id, Principal principal) {
        return restore.recheckFiles(id, principal);
    }

    @Operation(summary = "管理员解决文件缺件/多件差异（补齐来源说明）")
    @PostMapping("/file-diffs/{diffId}/resolve")
    public Map<String, Object> resolveDiff(@PathVariable long diffId,
                                           @Valid @RequestBody DiffResolveRequest req, Principal principal) {
        restore.resolveFileDiff(diffId, req.note(), principal);
        return Map.of("diffId", diffId, "status", "RESOLVED");
    }

    @Operation(summary = "缺主体关联字段位置：补齐 item_ref→subject_hash 来源映射后重检")
    @PostMapping("/locations/{id}/linkage")
    public Map<String, Object> supplyLinkage(@PathVariable long id,
                                             @Valid @RequestBody LinkageRequest req, Principal principal) {
        return restore.supplyLinkage(id,
            new RestoreReviewService.LinkageRequest(req.linkage(), req.assetCode()), principal);
    }

    @Operation(summary = "合规专员人工判定无法自动比对的复查项（SUPPRESS 隔离拦截 / RELEASE 放行）")
    @PostMapping("/review-items/{id}/decide")
    public Map<String, Object> decide(@PathVariable long id,
                                      @Valid @RequestBody ManualDecisionRequest req, Principal principal) {
        return restore.decideManualItem(id, req.decision(), req.note(), principal);
    }

    @Operation(summary = "登记恢复资产血缘（全部位置登记后才能申请开放）")
    @PostMapping("/batches/{id}/lineage")
    public Map<String, Object> lineage(@PathVariable long id, Principal principal) {
        return restore.registerLineage(id, principal);
    }

    @Operation(summary = "开放前再次核对删除凭证并提交开放申请（凭证指纹变化须先重扫）")
    @PostMapping("/batches/{id}/open-request")
    public Map<String, Object> submitOpen(@PathVariable long id,
                                          @Valid @RequestBody OpenRequest req, Principal principal) {
        return restore.submitOpenRequest(id, req.basis(), principal);
    }

    @Operation(summary = "合规批准开放：CLEAN 数据复制上线，命中删除凭证的残留隔离拦截并附记原工单")
    @PostMapping("/open-requests/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id,
                                       @Valid @RequestBody OpenDecisionRequest req, Principal principal) {
        return restore.approveOpenRequest(id, req.note(), principal);
    }

    @Operation(summary = "驳回开放申请，批次退回人工处理")
    @PostMapping("/open-requests/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id,
                                      @Valid @RequestBody OpenDecisionRequest req, Principal principal) {
        return restore.rejectOpenRequest(id, req.note(), principal);
    }
}
