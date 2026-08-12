# 보글사다리3 삼치기 Hedge

- PASS 없음: 매 회차 항상 삼치기 추천
- 목표: 4개 조합 중 가장 낮게 평가된 1개 조합 제외
- 엔진: 최근8/15/30, Markov-1/2, Binary 2-Bit, Regime Adaptive, 연속상태 조건
- Hedge: 각 엔진의 과거 삼치기 성공률에 따라 자동 가중
- 미래 데이터 누설 없는 walk-forward Hedge 백테스트
- 5,000원 기본 / 배당 수정 가능한 수익 계산
- 실전 삼치기 성공률 및 누적손익 기록
- 결과 최대 5,000회 누적 / JSON 백업·복원

API: https://api.bepick.io/game/bubble_ladder3

모델점수는 실제 당첨확률이 아닙니다. 결과가 독립적·무작위라면 장기 수익 우위를 보장할 수 없습니다.
