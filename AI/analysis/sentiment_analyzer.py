import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
import os
from typing import List

class SentimentPredictor:
    def __init__(self):
        self.model_path = "./model/sentiment_model"
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(
                f"오류: '{os.path.abspath(self.model_path)}' 경로를 찾을 수 없습니다."
            )
        
        print(f"'{self.model_path}'에서 감성 분석 모델을 불러오는 중...")
        self.tokenizer = AutoTokenizer.from_pretrained(self.model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(self.model_path)
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)
        self.model.eval()
        print(f"Using device for Sentiment: {self.device}")

    def predict(self, texts: List[str]) -> List[int]:
        print(f"{len(texts)}개의 텍스트에 대한 감성 분석 시작")
        if not texts:
            return []
        
        if not isinstance(texts, list):
            texts = [texts]

        inputs = self.tokenizer(texts, padding=True, truncation=True, return_tensors="pt")
        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            outputs = self.model(**inputs)
            predicted_labels_tensor = outputs.logits.argmax(dim=-1).detach().cpu()

        del inputs
        del outputs
        torch.cuda.empty_cache()
            
        return predicted_labels_tensor.tolist()