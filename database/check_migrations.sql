-- Kiểm tra DB đã chạy đủ các file migration chưa. Chạy: psql -U postgres -d scms_database -f database/check_migrations.sql
-- Mỗi dòng cho biết một migration đã chạy (OK) hay còn thiếu (THIẾU: chạy file đó theo thứ tự trong database/README.md).
-- Khi thêm migration mới, thêm một dòng kiểm tra ở cuối danh sách này.

SELECT file, CASE WHEN done THEN 'OK' ELSE 'THIẾU: chạy ' || file END AS ket_qua
FROM (VALUES
  ('v3_04_flow2_migration.sql',                   to_regclass('public.coach_slots') IS NOT NULL),
  ('v3_05_vouchers_and_remove_freeze.sql',        to_regclass('public.orders') IS NOT NULL),
  ('v3_06_flow4_training_attendance.sql',         to_regclass('public.training_results') IS NOT NULL),
  ('v3_08_one_on_one_room_sport.sql',             EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'coach_slots' AND column_name = 'room_id')),
  ('v3_10_remove_vouchers.sql',                   to_regclass('public.vouchers') IS NULL),
  ('v3_11_remove_coach_rank.sql',                 NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'coach_profiles' AND column_name = 'coach_rank')),
  ('v3_12_coach_slot_requests.sql',               to_regclass('public.coach_slot_requests') IS NOT NULL),
  ('v3_13_reschedule_remove_open_slots.sql',      EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'coach_slots' AND column_name = 'needs_reschedule')),
  ('v3_14_class_auto_open_remove_early_register.sql', NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'membership_packages' AND column_name = 'early_register_days')),
  ('v3_15_request_weekdays.sql',                  to_regclass('public.coach_slot_request_days') IS NOT NULL),
  ('v3_16_remove_ai.sql',                         NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'membership_packages' AND column_name = 'ai_level')),
  ('v3_17_refund_claim_code.sql',                 EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'refunds' AND column_name = 'claim_code_sent_at')),
  ('v3_18_payment_24h_and_request_slots.sql',     (SELECT count(*) = 3 FROM system_settings WHERE key IN ('one_on_one.request_pay_hours', 'waitlist.hold_hours', 'waitlist.hold_hours_near_start') AND value = '24') AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_request_day' AND pg_get_constraintdef(oid) LIKE '%weekday, time_slot_id%')),
  ('v3_19_coach_slot_note.sql',                    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'coach_slots' AND column_name = 'coach_note' AND character_maximum_length = 1000))
) AS t(file, done);
