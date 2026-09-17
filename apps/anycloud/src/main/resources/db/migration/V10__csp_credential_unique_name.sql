-- 같은 provider 안에서 자격증명 이름을 유일하게 만든다.
-- 이름만으로는 구분이 안 돼 프로비저닝에서 어느 것을 고른 건지 알 수 없었다.
--
-- 기존 중복은 지우지 않고 이름을 바꿔 보존한다. 자격증명은 사용자가 등록한 자산이고,
-- 어느 것이 쓰이고 있는지 마이그레이션이 알 수 없다.
-- provider 는 등록 시 표기가 섞여 있어(AWS / aws) 대소문자 무시로 묶는다.
UPDATE csp_credential c
    JOIN (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY LOWER(provider), LOWER(name)
                   ORDER BY created_at, id
               ) AS rn
        FROM csp_credential
    ) d ON d.id = c.id
SET c.name = CONCAT(LEFT(c.name, 92), '-', d.rn)
WHERE d.rn > 1;

CREATE UNIQUE INDEX uk_csp_credential_provider_name ON csp_credential (provider, name);
