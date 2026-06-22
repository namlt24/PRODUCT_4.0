package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.TemplateCharUse;
import com.telecom.bccs.management.catalog.service.TemplateCharUseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho TEMPLATE_CHAR_USE. */
@RestController
@RequestMapping("/api/v1/management/template-char-uses")
public class TemplateCharUseController {

    private final TemplateCharUseService service;

    public TemplateCharUseController(TemplateCharUseService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateCharUse> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public TemplateCharUse get(@PathVariable Long id) {
        return service.findById(id);
    }
}
