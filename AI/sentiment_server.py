import pandas as pd
from datasets import Dataset
from transformers import AutoTokenizer, AutoModelForSequenceClassification
import torch
import os
import socket
import json
import threading

HOST = '127.0.0.1'
PORT = 8000

class SentimentPredictor:
    def __init__(self):
        self.model_path = "./model/sentiment_model"
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(
                f"오류: '{os.path.abspath(self.model_path)}' 경로를 찾을 수 없습니다. 모델을 먼저 학습시키고 저장했는지 확인해주세요."
            )

        # 저장된 모델과 토크나이저 불러오기
        print(f"'{self.model_path}'에서 모델과 토크나이저를 불러오는 중...")
        self.tokenizer = AutoTokenizer.from_pretrained(self.model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(self.model_path)

        # 'cuda'가 사용 가능한지 확인하여 장치 설정 (GPU 또는 CPU)
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)  # 모델을 올바른 장치로 이동
        self.model.eval()  # 모델을 평가 모드로 전환
        print(f"Using device: {self.device}")

    def predict(self, texts):
        """
        댓글 리스트를 입력받아 감성 라벨을 반환합니다.
        """
        print("분석 시작")
        if not isinstance(texts, list):
            texts = [texts]  # 단일 문자열도 리스트로 변환

        # 토큰화 및 GPU 이동
        inputs = self.tokenizer(texts, padding=True, truncation=True, return_tensors="pt")
        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        # 모델 예측
        with torch.no_grad():
            outputs = self.model(**inputs)
            predicted_labels_tensor = outputs.logits.argmax(dim=-1)

        return predicted_labels_tensor.tolist()

def sentiment(conn, addr):
    print(f"클라이언트가 연결되었습니다.: {addr}")
    try:
        while True:
            with conn.makefile("r", encoding="utf-8") as f:
                json_string = f.readline().strip()
                request_body = json.loads(json_string)
                comment_objects = request_body['comments']
                comment_texts = [comment['text'] for comment in comment_objects]
            
                results = predictor.predict(comment_texts)
                for comment_dict, label in zip(comment_objects, results):
                    comment_dict['prediction'] = label
                response_json = json.dumps(request_body, ensure_ascii=False)
                conn.sendall((response_json + "\n").encode("utf-8"))

    finally:
        print(f"클라이언트와의 연결을 종료합니다: {addr}")
        conn.close()
        
def server_start():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind((HOST, PORT))
    server_socket.listen()
    server_socket.settimeout(1.0)

    try:
        while True:
            try:
                conn, addr = server_socket.accept()
                client = threading.Thread(target=sentiment, args=(conn, addr))
                client.start()
            except socket.timeout:
                continue
        
    
    except KeyboardInterrupt:
        print("\n 서버를 종료합니다.")
    finally:
        server_socket.close()

if __name__ == "__main__":
    predictor = SentimentPredictor()
    server_start()
