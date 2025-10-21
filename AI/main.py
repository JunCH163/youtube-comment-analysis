from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
import uvicorn

# predictors.py 파일에서 우리가 만든 클래스들을 가져옵니다.
from analysis.sentiment_analyzer import SentimentPredictor
from analysis.bot_detector import BotDetector
from analysis.keyword_extractor import KeywordExtractor

# --- 1. 데이터 유효성 검사를 위한 모델 정의 (Pydantic) ---
class Comment(BaseModel):
    id: str
    author: str
    text: str
    likeCount: int
    publishedAt: str
    prediction: int

class Trace(BaseModel):
    requestId: str
    analysisETag: str

class AnalysisRequest(BaseModel):
    comments: List[Comment]
    trace: Trace

# --- 2. FastAPI 앱 생성 및 모델 로딩 ---
app = FastAPI()

bot_detector = BotDetector()
sentiment_predictor = SentimentPredictor()
keyword_extractor = KeywordExtractor()

# --- 3. API 엔드포인트(Endpoint) 생성 ---
@app.post("/analyze/comments")
def analyze_comments(request: AnalysisRequest):
    """
    댓글 목록을 받아 감성 분석 결과를 추가하여 반환합니다.
    """
    comment_objects = request.comments
    
    comment_texts = [comment.text for comment in comment_objects]

    # 봇 판별
    detected_bot_labels = bot_detector.detect(comment_texts)

    # 봇 댓글 제거 및 비율 산정
    detected_bot_count = sum(detected_bot_labels)
    human_objects = [comment for comment, label in zip(comment_objects, detected_bot_labels) if label == 0]

    human_texts = [comment.text for comment in human_objects]

    # 감정 분석 실행
    predicted_labels = sentiment_predictor.predict(human_texts)
    
    # 키워드 추출 실행
    top_keywords = keyword_extractor.extract(human_texts)
    
    # 결과 병합
    for i, comment in enumerate(human_objects):
        comment.prediction = predicted_labels[i]
    
    # 최종 응답 객체 생성
    final_response = {
        "comments": [c.model_dump() for c in human_objects],
        "trace": request.trace.model_dump(),
        "detectedBotCount": detected_bot_count,
        "topKeyword": top_keywords
    }
    
    return final_response


# ... FastAPI 앱 정의 코드 ...
if __name__ == "__main__":
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=False)