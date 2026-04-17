package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.BatchResultResponse;
import com.kama.jchatmind.model.response.BatchSubmitResponse;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    BatchSubmitResponse uploadDocumentsBatch(String kbId, MultipartFile[] files);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
