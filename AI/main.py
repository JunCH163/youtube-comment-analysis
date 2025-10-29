import asyncio
import time
from typing import List, Dict, Any, Tuple, Union
from contextlib import asynccontextmanager
import uvicorn

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from analysis.sentiment_analyzer import SentimentPredictor
from analysis.bot_detector import BotDetector
from analysis.keyword_extractor import KeywordExtractor

# --- 1. 데이터 모델 정의 ---
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

# --- 2. 블로킹(동기) AI 추론 로직 ---
def blocking_batch_analysis(all_comments: List[Comment]) -> Tuple[List[int], Dict[int, int]]:
    """
    하나의 큰 배치에 대해 동기적으로 AI 모델을 실행하는 함수.
    """
    if not all_comments:
        return [], {}

    all_comment_texts = [c.text for c in all_comments]

    # 봇 판별
    detected_bot_labels = bot_detector.detect(all_comment_texts)

    # 봇이 아닌 댓글만 필터링
    human_indices = [i for i, label in enumerate(detected_bot_labels) if label == 0]
    human_texts = [all_comment_texts[i] for i in human_indices]

    # 감정 분석 (봇 아닌 댓글 대상)
    predicted_sentiment_labels = []
    if human_texts: # 봇 아닌 댓글이 있을 때만 실행
        predicted_sentiment_labels = sentiment_predictor.predict(human_texts)

    # 결과를 원래 댓글의 전체 인덱스에 다시 매핑
    sentiment_results_map = {
        human_indices[i]: label
        for i, label in enumerate(predicted_sentiment_labels)
    }

    return detected_bot_labels, sentiment_results_map

# --- 3. 비동기 배치 프로세서 클래스 ---
QueueItem = Tuple[AnalysisRequest, asyncio.Future]

class AnalysisBatchProcessor:
    def __init__(self, max_batch_size: int, batch_timeout: float):
        self.max_batch_size = max_batch_size
        self.batch_timeout = batch_timeout
        self.queue: asyncio.Queue[QueueItem] = asyncio.Queue()
        self._worker_task: Union[asyncio.Task, None] = None

    def start_worker(self):
        """백그라운드 워커 태스크 시작"""
        if self._worker_task is None or self._worker_task.done():
            self._worker_task = asyncio.create_task(self._run_batch_worker())
            print(f"Batch processor worker started with batch size {self.max_batch_size}.")

    async def stop_worker(self):
        """백그라운드 워커 태스크 안전 종료"""
        if self._worker_task:
            await self.queue.put((None, None)) # type: ignore
            try:
                await asyncio.wait_for(self._worker_task, timeout=5.0)
            except asyncio.TimeoutError:
                self._worker_task.cancel()
            self._worker_task = None
        print("Batch processor worker stopped.")

    async def _run_batch_worker(self):
        """큐에서 요청을 모아 배치를 만들고 처리 시작"""
        while True:
            try:
                first_item: QueueItem = await self.queue.get()
                if first_item[0] is None: # 종료 신호
                    break

                batch: List[QueueItem] = [first_item]
                start_time = time.monotonic()
                current_batch_comment_count = len(first_item[0].comments) # 댓글 수 기준

                while (current_batch_comment_count < self.max_batch_size and
                       (time.monotonic() - start_time) < self.batch_timeout):
                    try:
                        item: QueueItem = self.queue.get_nowait()
                        if item[0] is None: # 종료 신호 중간 유입
                            await self.queue.put(item)
                            break

                        # 댓글 수를 더해도 배치 크기를 넘지 않는지 확인
                        if current_batch_comment_count + len(item[0].comments) <= self.max_batch_size:
                           batch.append(item)
                           current_batch_comment_count += len(item[0].comments)
                        else:
                            await self.queue.put(item) # 다음 배치를 위해 큐에 다시 넣음
                            break
                    except asyncio.QueueEmpty:
                        await asyncio.sleep(0.005) # CPU 낭비 방지

                # 워커 루프를 막지 않도록 새 태스크 생성
                asyncio.create_task(self.process_batch(batch))

            except Exception as e:
                print(f"CRITICAL: Batch worker loop failed: {e}")
                await asyncio.sleep(1) # 오류 시 잠시 대기

    async def process_batch(self, batch: List[QueueItem]):
        """배치를 받아 AI 추론을 스레드 풀에 위임하고 결과 분배"""
        all_comments: List[Comment] = []
        request_indices: List[int] = [] # 각 댓글이 원본 batch의 몇 번째 요청에서 왔는지 기록

        for i, (request_obj, _) in enumerate(batch):
            all_comments.extend(request_obj.comments)
            request_indices.extend([i] * len(request_obj.comments))

        # --- 배치 처리 시간 측정 시작 ---
        process_start_time = time.monotonic()
        total_comments_in_batch = len(all_comments)
        # --------------------------------

        try:
            # AI 추론 (블로킹 함수 -> 별도 스레드에서 실행)
            detected_bot_labels, sentiment_results_map = await asyncio.to_thread(
                blocking_batch_analysis, all_comments
            )

            # 결과 재조립
            comment_global_index = 0
            for i, (request_obj, future) in enumerate(batch): # 각 원본 요청 순회

                human_objects: List[Comment] = []
                detected_bot_count = 0
                num_comments_in_request = len(request_obj.comments)

                for k in range(num_comments_in_request): # 현재 요청 내 댓글 순회
                    original_idx = comment_global_index + k

                    # 인덱스 범위 확인
                    if original_idx >= len(detected_bot_labels):
                        print(f"Warning: Index out of range ({original_idx}) for detected_bot_labels (len={len(detected_bot_labels)}). Skipping comment.")
                        continue

                    comment = request_obj.comments[k] # 원본 객체 사용

                    if detected_bot_labels[original_idx] == 1: # 봇 판별 결과
                        detected_bot_count += 1
                    else: # 봇이 아닐 경우
                        if original_idx in sentiment_results_map: # 감성분석 결과 매핑
                            comment.prediction = sentiment_results_map[original_idx]
                        # prediction 값이 없더라도 봇이 아니면 리스트에 포함
                        human_objects.append(comment)

                comment_global_index += num_comments_in_request # 다음 요청 시작 인덱스로 이동

                # 키워드 추출 (각 요청별 인간 댓글 대상)
                human_texts = [c.text for c in human_objects]
                top_keywords = keyword_extractor.extract(human_texts) if keyword_extractor and human_texts else []

                # 최종 응답 구성
                final_response = {
                    "comments": [c.model_dump() for c in human_objects],
                    "trace": request_obj.trace.model_dump(),
                    "detectedBotCount": detected_bot_count,
                    "topKeyword": top_keywords
                }
                if not future.done(): # Future가 완료되지 않았을 때만 결과 설정
                    future.set_result(final_response)

        except Exception as e:
            print(f"ERROR: Batch processing failed: {e}")
            # 배치 내 모든 요청에 에러 전파
            for _, future in batch:
                if not future.done():
                    future.set_exception(e)

        # --- 배치 처리 시간 측정 종료 및 로깅 ---
        process_end_time = time.monotonic()
        elapsed_time = process_end_time - process_start_time
        throughput = total_comments_in_batch / elapsed_time if elapsed_time > 0 else 0
        print(f"BATCH PROCESSED | Size: {total_comments_in_batch} | Requests: {len(batch)} | Time: {elapsed_time:.4f}s | Throughput: {throughput:.2f} c/s")
        # ---------------------------------------

    async def submit_request(self, request: AnalysisRequest) -> Dict[str, Any]:
        """요청을 큐에 넣고 결과를 기다림"""
        future: asyncio.Future[Dict[str, Any]] = asyncio.Future()
        await self.queue.put((request, future))
        return await future # 결과가 Future에 설정될 때까지 비동기 대기

# --- 4. FastAPI 앱 수명 주기(lifespan) 관리 ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 앱 시작 시 모델 로드 및 워커 실행
    global bot_detector, sentiment_predictor, keyword_extractor, batch_processor
    try:
        bot_detector = BotDetector()
        sentiment_predictor = SentimentPredictor()
        keyword_extractor = KeywordExtractor()
        print("Models loaded successfully.")
    except Exception as e:
        print(f"CRITICAL: Error loading models during startup: {e}")
        # 모델 로딩 실패 시 서버 시작 중단 또는 에러 상태 설정 필요
        # 예: raise SystemExit("Failed to load models")
        bot_detector, sentiment_predictor, keyword_extractor = None, None, None # 임시 조치

    batch_processor = AnalysisBatchProcessor(max_batch_size=256, batch_timeout=0.5)
    batch_processor.start_worker()

    yield # 앱 실행 구간

    # 앱 종료 시 워커 중지
    await batch_processor.stop_worker()
    print("Batch processor worker stopped.")

# --- 5. FastAPI 앱 생성 ---
# lifespan을 사용하여 앱 시작/종료 시 로직 관리
app = FastAPI(lifespan=lifespan)

# 전역 변수 선언 (lifespan 내에서 초기화됨)
bot_detector: Union[BotDetector, None] = None
sentiment_predictor: Union[SentimentPredictor, None] = None
keyword_extractor: Union[KeywordExtractor, None] = None
batch_processor: Union[AnalysisBatchProcessor, None] = None

# --- 6. API 엔드포인트 생성 ---
@app.post("/analyze/comments")
async def analyze_comments(request: AnalysisRequest):
    """(비동기 접수) 댓글 목록 요청을 배치 큐에 넣고 처리 결과를 기다려 반환합니다."""
    # --- [요청 처리 시간 측정 시작] ---
    request_start_time = time.monotonic()
    num_comments_received = len(request.comments)
    # -----------------------------------

    # 모델 로딩 실패 시 에러 반환
    if not bot_detector or not sentiment_predictor or not keyword_extractor or not batch_processor:
         raise HTTPException(status_code=503, detail="Service Unavailable: Models or processor not loaded.")

    try:
        # submit_request는 결과를 받을 때까지 비동기 대기
        result = await batch_processor.submit_request(request)

        # --- [요청 처리 시간 측정 종료 및 로깅] ---
        request_end_time = time.monotonic()
        total_request_time = request_end_time - request_start_time
        print(f"REQUEST COMPLETED | RequestID: {request.trace.requestId} | Comments: {num_comments_received} | Total Time: {total_request_time:.4f}s")
        # ---------------------------------------

        return result

    except Exception as e:
        # submit_request 또는 내부 처리 중 발생한 예외 처리
        print(f"ERROR processing request {request.trace.requestId}: {e}")
        raise HTTPException(status_code=500, detail=f"Internal server error during analysis: {e}")


# --- 7. 서버 실행 (개발용) ---
if __name__ == "__main__":
    # uvicorn main:app --host 127.0.0.1 --port 8000 --workers 4
    # uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=False, workers=4)
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=False)

