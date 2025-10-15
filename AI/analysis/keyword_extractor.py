# analysis/keyword_extractor.py

from collections import Counter
from konlpy.tag import Okt
from typing import List, Dict, Union

class KeywordExtractor:
    def __init__(self):
        print("키워드 추출기(Okt)를 초기화하는 중...")
        self.okt = Okt()
        # 자주 등장하지만 의미 없는 한 글자 명사들을 불용어로 추가
        self.stopwords = {'것', '수', '저', '제', '좀', '중'}
        print("키워드 추출기 초기화 완료.")

    def extract(self, texts: List[str]) -> List[Dict[str, Union[str, int]]]:
        # 키워드 추출 개수
        top_n = 20
        print(f"{len(texts)}개의 텍스트에서 키워드 추출 시작...")
        all_nouns = []

        # 모든 댓글을 순회하며 명사 추출
        for text in texts:
            # 형태소 분석기를 사용해 명사만 추출
            nouns = self.okt.nouns(text)
            
            # 필터링: 두 글자 이상이고, 불용어에 포함되지 않은 명사만 선택
            filtered_nouns = [
                noun for noun in nouns 
                if len(noun) > 1 and noun not in self.stopwords
            ]
            all_nouns.extend(filtered_nouns)

        # 모든 명사의 빈도수 계산
        if not all_nouns:
            return [] # 추출된 명사가 없으면 빈 리스트 반환

        counter = Counter(all_nouns)

        # 가장 많이 등장한 상위 N개 키워드를 튜플 리스트로 가져옴
        top_keyword_tuples = counter.most_common(top_n)
        
        # 클라이언트가 사용하기 편하도록 딕셔너리 리스트 형태로 변환
        result = [
            {"keyword": keyword, "count": count} 
            for keyword, count in top_keyword_tuples
        ]
        
        print(f"상위 {len(result)}개 키워드 추출 완료.")
        return result

