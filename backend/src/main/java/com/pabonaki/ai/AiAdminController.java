package com.pabonaki.ai;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Admin-only (covered by the existing /api/v1/admin/** ROLE_ADMIN rule): index status and manual (re)indexing. */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AiAdminController {
    private final ProductIndexer indexer;

    public AiAdminController(ProductIndexer indexer) { this.indexer = indexer; }

    @GetMapping("/status")
    public Map<String, Object> status() { return indexer.status(); }

    /**
     * Backfills / refreshes embeddings in a bounded batch (default 25, max 200) so a single call stays within provider quotas.
     * Call it repeatedly until needingWork reaches processed. force=true re-embeds products even if nothing changed.
     */
    @PostMapping("/reindex")
    public ProductIndexer.SweepResult reindex(@RequestParam(value = "force", defaultValue = "false") boolean force,
                                              @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return indexer.sweep(force, Math.max(1, Math.min(limit, 200)));
    }
}
