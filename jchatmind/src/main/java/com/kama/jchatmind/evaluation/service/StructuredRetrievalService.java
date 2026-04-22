package com.kama.jchatmind.evaluation.service;

import com.kama.jchatmind.evaluation.model.StructuredRetrievalResult;

public interface StructuredRetrievalService {
    StructuredRetrievalResult retrieve(String kbId, String query, int topN);
}
