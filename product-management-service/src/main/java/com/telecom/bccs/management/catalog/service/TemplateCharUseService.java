package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.TemplateCharUse;
import com.telecom.bccs.management.catalog.repository.TemplateCharUseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc TEMPLATE_CHAR_USE (chỉ tra cứu). */
@Service
public class TemplateCharUseService {

    private final TemplateCharUseRepository repository;

    public TemplateCharUseService(TemplateCharUseRepository repository) {
        this.repository = repository;
    }

    public List<TemplateCharUse> findAll() {
        return repository.findAll();
    }

    public TemplateCharUse findById(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TemplateCharUse not found: " + id));
    }
}
