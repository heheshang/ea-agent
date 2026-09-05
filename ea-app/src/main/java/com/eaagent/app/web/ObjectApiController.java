package com.eaagent.app.web;

import com.eaagent.api.dto.ObjectQueryRequest;
import com.eaagent.common.PageResult;
import com.eaagent.common.Result;
import com.eaagent.ontology.service.ObjectApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统一对象 API（3.2）：/api/objects/{type} 查询（cursor 分页）/单对象/链接出边。
 * 白名单字段投影与动态安全过滤在 ObjectApiService 内完成。
 */
@RestController
@RequestMapping("/api/objects")
public class ObjectApiController {

    private final ObjectApiService objectApi;

    public ObjectApiController(ObjectApiService objectApi) {
        this.objectApi = objectApi;
    }

    @GetMapping("/{type}")
    public Result<PageResult<Map<String, Object>>> list(
            @PathVariable String type,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String keyword) {
        ObjectQueryRequest req = new ObjectQueryRequest();
        req.setFilter(filter);
        req.setSort(sort);
        req.setPageToken(pageToken);
        req.setLimit(limit);
        req.setKeyword(keyword);
        return Result.ok(objectApi.search(type, req));
    }

    @PostMapping("/{type}/search")
    public Result<PageResult<Map<String, Object>>> search(@PathVariable String type,
                                                          @RequestBody ObjectQueryRequest req) {
        return Result.ok(objectApi.search(type, req));
    }

    @GetMapping("/{type}/{id}")
    public Result<Map<String, Object>> get(@PathVariable String type, @PathVariable Long id) {
        return Result.ok(objectApi.get(type, id));
    }

    /** 画像更新（仅 customer：attributes/tags 整表替换，详见 ObjectApiService.update）。 */
    @PutMapping("/{type}/{id}")
    public Result<Map<String, Object>> update(@PathVariable String type, @PathVariable Long id,
                                              @RequestBody java.util.Map<String, Object> patch) {
        return Result.ok(objectApi.update(type, id, patch));
    }

    @GetMapping("/{type}/{id}/links/{link}")
    public Result<List<Map<String, Object>>> links(@PathVariable String type, @PathVariable Long id,
                                                   @PathVariable String link) {
        return Result.ok(objectApi.links(type, id, link));
    }
}