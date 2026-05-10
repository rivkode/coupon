-- 부하 테스트용 마스터 데이터 시드.
-- run-integrated.sh 가 docker exec 로 실행. ${TOTAL_INVENTORY} 는 sed 로 치환됨.
--
-- 멱등성: 매 run 마다 user_coupon / outbox_event 는 비우고, inventory 는 reset.
-- event / coupon_type 은 INSERT IGNORE 로 처음 한 번만 생성.

USE server_c;

-- 이전 run 의 발급 흔적 정리 (집계 정확성 확보).
DELETE FROM user_coupon;
DELETE FROM outbox_event;

-- 마스터 데이터 (없으면 생성).
INSERT IGNORE INTO event (event_id, name, content, started_at, ended_at)
    VALUES (1, 'load-test', 'k6 integrated', NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 1 HOUR);

INSERT IGNORE INTO coupon_type (coupon_type_id, event_id, name, discount_rate)
    VALUES (1, 1, '10pct', 10);

-- 재고 row — 매 run 마다 reset (idempotent).
INSERT INTO coupon_type_inventory (event_id, coupon_type_id, total_inventory, available_count)
    VALUES (1, 1, ${TOTAL_INVENTORY}, ${TOTAL_INVENTORY})
    ON DUPLICATE KEY UPDATE
        total_inventory = ${TOTAL_INVENTORY},
        available_count = ${TOTAL_INVENTORY};
