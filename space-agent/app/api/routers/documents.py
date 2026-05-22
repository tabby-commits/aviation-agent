from fastapi import APIRouter, Depends, File, Form, UploadFile

from app.api.deps import get_store
from app.core.responses import success
from app.schemas.document import CreateDocumentRequest, UpdateDocumentRequest

router = APIRouter(tags=["documents"])


@router.get("/documents")
async def get_documents(store=Depends(get_store)):
    documents = await store.list_documents()
    return success({"documents": [doc.to_public_dict() for doc in documents]})


@router.get("/documents/kb/{kb_id}")
async def get_documents_by_kb(kb_id: str, store=Depends(get_store)):
    documents = await store.list_documents(kb_id=kb_id)
    return success({"documents": [doc.to_public_dict() for doc in documents]})


@router.post("/documents")
async def create_document(request: CreateDocumentRequest, store=Depends(get_store)):
    document = await store.create_document(request)
    return success({"documentId": document.id})


@router.post("/documents/upload")
async def upload_document(
    kbId: str = Form(...),
    file: UploadFile = File(...),
    store=Depends(get_store),
):
    document = await store.create_uploaded_document(kbId, file)
    return success({"documentId": document.id})


@router.post("/documents/upload/batch")
async def upload_documents_batch(
    kbId: str = Form(...),
    files: list[UploadFile] = File(...),
):
    return success({"batchId": kbId, "total": len(files)})


@router.delete("/documents/{document_id}")
async def delete_document(document_id: str, store=Depends(get_store)):
    await store.delete_document(document_id)
    return success()


@router.patch("/documents/{document_id}")
async def update_document(
    document_id: str, request: UpdateDocumentRequest, store=Depends(get_store)
):
    await store.update_document(document_id, request)
    return success()
