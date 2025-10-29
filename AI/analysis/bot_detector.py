import torch
import os
from typing import List, Any
from XDAC_obs.xdac_encrypted import AIUnifiedEngine, get_xdac_path

class BotDetector:
    def __init__(self):
        self.model_path = get_xdac_path()
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(
                f"오류: '{os.path.abspath(self.model_path)}' 경로를 찾을 수 없습니다."
            )
        
        print(f"'{self.model_path}'에서 봇 탐지 모델(XDAC)을 불러오는 중...")
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        
        # XDAC 통합 엔진 초기화
        self.engine = AIUnifiedEngine(
            detection_model_path=os.path.join(self.model_path, 'XDAC-D'),
            attribution_model_path=os.path.join(self.model_path, 'XDAC-A'),
            device=self.device,
            xai_enabled=False
        )
        print(f"Using device for Bot Detection: {self.device}")

    def detect(self, texts: List[str]) -> List[int]:
        if not texts:
            return []

        if not isinstance(texts, list):
            texts = [texts]

        print(f"{len(texts)}개의 댓글에 대해 XDAC 봇 탐지 시작...")
        
        # --- XDAC 모델을 이용한 직접 추론 ---
        with torch.no_grad():
            results = self.engine.predict(texts, batch_size=len(texts))
            bot_labels = [1 if (result.get('pred_label') == 'LLM') & (result.get('pred_score') >= 80.0) else 0 for result in results]
        
        del results
        torch.cuda.empty_cache()

        print("봇 탐지 완료.")
        return bot_labels