-- 저장된 확인 결과를 다시 읽을 때 조회 리전 수가 늘 0 으로 나갔다. 10분 안에 다시 물으면
-- "정상인데 아무 리전도 못 봤다" 로 읽혀 자격증명이 반쯤 망가진 것처럼 보인다.
ALTER TABLE csp_credential
    ADD COLUMN health_checked_regions INT NULL;
