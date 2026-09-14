# Backend Requirement: 오디 오디오 및 AI 도슨트 서비스 (`BE-REQ-ODII`)

> **Requirement Group**: `BE-REQ-ODII`  
> **Source Features**: `ODII-F001`, `ODII-F002`, `ODII-F003`, `ODII-F004`  
> **Target Consumer**: `/odii` Page

---

## 1. Requirement Inventory

| Requirement ID | Capability | HTTP Method & Candidate Endpoint | Read/Write | Note |
| :--- | :--- | :--- | :---: | :--- |
| **`BE-REQ-008`** | 오디 오디오 스토리 목록 및 챕터 정보 조회 | `GET /api/v1/odii/stories` | Read | TourAPI Odii Adapter & Cache |
| **`BE-REQ-009`** | 실시간 오디오 대본 기반 AI 도슨트 질의응답 (RAG) | `POST /api/v1/odii/ask` | Exec/Stream | LLM Inference (SSE Streaming) |

---

## 2. API Specifications

### 2.1 AI 도슨트 질의응답 (`BE-REQ-009`)
- **Request Body**:
  ```json
  {
    "storyId": "ST12345",
    "storyTitle": "경복궁 집옥재의 고즈넉한 서가 이야기",
    "scriptContext": "고종 황제가 서재이자 외국 사신 접견소로 사용했던 집옥재는...",
    "question": "집옥재는 일반적인 한옥과 건축 양식이 어떻게 다른가요?"
  }
  ```
- **Response**: Server-Sent Events (SSE) 텍스트 스트리밍 또는 JSON 응답
