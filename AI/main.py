from fastapi import FastAPI
from pydantic import BaseModel
from typing import List

# predictors.py 파일에서 우리가 만든 클래스들을 가져옵니다.
from analysis.sentiment_analyzer import SentimentPredictor

# --- 1. 데이터 유효성 검사를 위한 모델 정의 (Pydantic) ---
class Comment(BaseModel):
    id: str
    text: str

class Trace(BaseModel):
    requestId: str
    etag: str

class AnalysisRequest(BaseModel):
    comments: List[Comment]
    trace: Trace

# --- 2. FastAPI 앱 생성 및 모델 로딩 ---
app = FastAPI()

sentiment_predictor = SentimentPredictor()

# --- 3. API 엔드포인트(Endpoint) 생성 ---
@app.post("/analyze/comments")
def analyze_comments(request: AnalysisRequest):
    """
    댓글 목록을 받아 감성 분석 결과를 추가하여 반환합니다.
    """
    comment_objects = request.comments
    
    comment_texts = [comment.text for comment in comment_objects]
    predicted_labels = sentiment_predictor.predict(comment_texts)
    
    # 결과 병합
    response_comments = []
    for i, comment in enumerate(comment_objects):
        comment_dict = comment.model_dump()
        comment_dict['sentiment_label'] = predicted_labels[i]
        response_comments.append(comment_dict)
    
    # 최종 응답 객체 생성
    final_response = {
        "comments": response_comments,
        "trace": request.trace.model_dump()
    }
    
    return final_response

@app.get("/")
def health_check():
    return {"status": "OK", "message": "Sentiment Analysis Server is running."}