package com.kama.jchatmind.evaluation.service;

import com.kama.jchatmind.evaluation.request.ExportEvaluationRetrievalRequest;
import com.kama.jchatmind.evaluation.response.ExportEvaluationRetrievalResponse;

public interface EvaluationRetrievalExportService {
    ExportEvaluationRetrievalResponse exportRetrieval(ExportEvaluationRetrievalRequest request);
}
