# Hướng dẫn dựng và cập nhật database

Dành cho thành viên nhóm và cho Claude/AI đang hỗ trợ thành viên. Cả hai cách dưới đây đã được chạy thử từ DB trống và ra
đúng schema mà code BE đang dùng (PostgreSQL 18, cần extension `btree_gist`, có sẵn trong bản cài PostgreSQL).

## Cách nhanh nhất: nạp một file duy nhất (khuyến nghị cho máy mới hoặc khi DB đang lỗi)

Tạo DB trống rồi nạp `demo_dump.sql`. File này đã gồm schema mới nhất (tới `v3_18`) và dữ liệu demo nhỏ.

```bash
psql -U postgres -c "DROP DATABASE IF EXISTS scms_database"
psql -U postgres -c "CREATE DATABASE scms_database"
psql -U postgres -d scms_database -v ON_ERROR_STOP=1 -f database/demo_dump.sql
```

Trên Windows nếu `psql` không có trong PATH, dùng `"C:\Program Files\PostgreSQL\18\bin\psql.exe"`.
Mật khẩu mặc định của BE là biến môi trường `DB_PASSWORD` (mặc định `123`), user `postgres`, DB `scms_database`
(xem `src/main/resources/application.properties`, có thể đổi bằng `DB_URL`, `DB_USER`, `DB_PASSWORD`).

Xong là đăng nhập được ngay. Mọi tài khoản demo dùng mật khẩu `12345678`:

| Vai trò | Email |
|---|---|
| Manager | `manager@demo.local` |
| Lễ tân | `letan@demo.local` |
| Coach | `hung.le@demo.local`, `trang.pham@demo.local`, `vinh.do@demo.local`, `yen.nguyen@demo.local`, `khoi.vo@demo.local` |
| Hội viên | `han.bui@`, `quan.dang@`, `thao.ngo@`, `anh.trinh@`, `chau.ly@`, `viet.phan@`, `ngan.hoang@`, `khang.vu@`, `bich.dinh@`, `son.cao@`, `linh.luong@`, `phuoc.ta@`, `vy.mai@`, `tin.duong@` (thêm `demo.local` ở cuối; `tin.duong` đang bị khóa) |

## Cách từ schema gốc và migration (khi cần giữ DB cũ hoặc muốn hiểu từng bước)

Chạy **đúng thứ tự** dưới đây. Mỗi file migration chạy lại được (an toàn nếu đã chạy). Dừng ngay khi có lỗi, đừng chạy tiếp.

```
schema_v3.sql                                  (chỉ cho DB trống)
v3_04_flow2_migration.sql
v3_05_vouchers_and_remove_freeze.sql
v3_06_badminton_zumba.sql
v3_06_flow4_training_attendance.sql
v3_06_remove_fitness_assessment.sql
v3_07_flow4_revision.sql
v3_07_package_descriptions.sql
v3_08_one_on_one_room_sport.sql
v3_09_one_on_one_slot_minutes.sql
v3_10_remove_vouchers.sql
v3_11_remove_coach_rank.sql
v3_12_coach_slot_requests.sql
v3_13_reschedule_remove_open_slots.sql
v3_14_class_auto_open_remove_early_register.sql
v3_15_request_weekdays.sql
v3_16_remove_ai.sql
v3_17_refund_claim_code.sql
v3_18_payment_24h_and_request_slots.sql
```

Ví dụ chạy một file: `psql -U postgres -d scms_database -v ON_ERROR_STOP=1 -f database/v3_16_remove_ai.sql`

Muốn có dữ liệu demo sau khi dựng schema: chạy thêm `demo_seed_small.sql`. **File này XÓA SẠCH toàn bộ người dùng và dữ liệu
giao dịch** rồi tạo lại (giữ nguyên danh mục: vai trò, môn, phòng, khung giờ, gói thành viên, cấu hình), nên không chạy trên DB
đang có dữ liệu thật cần giữ. Chạy lại nhiều lần được, ngày tháng tự tính theo hôm nay.

## Sau khi `git pull` mà BE báo lỗi DB (cột/bảng không tồn tại, 500 khi gọi API)

Gần như luôn do có migration mới mà DB của bạn chưa chạy. Làm theo thứ tự:

1. Xem có file mới nào trong `database/` không: `git log --stat -5 -- database/`.
2. Chạy kiểm tra: `psql -U postgres -d scms_database -f database/check_migrations.sql`. Dòng nào ghi `THIẾU` thì chạy file đó
   (và các file đứng sau nó trong danh sách ở trên).
3. Nếu vẫn lỗi hoặc không chắc: dựng lại DB bằng "Cách nhanh nhất" ở đầu file. Mất dữ liệu bạn tự thêm trên máy, nhưng chắc chắn khớp code.
4. Khởi động lại BE sau khi sửa DB (Hibernate không tự đổi schema: `spring.jpa.hibernate.ddl-auto=none`).

## Lỗi thường gặp

- `invalid command \restrict`: do file dump sinh từ `pg_dump` bản mới có hai dòng `\restrict` và `\unrestrict` mà `psql` bản cũ không hiểu.
  Hai file trong repo đã được gỡ sẵn các dòng này. Nếu tự `pg_dump` ra file mới thì xóa hai dòng đó trước khi commit.
- Dữ liệu bị dính ký tự lạ ở cuối dòng sau khi nạp `demo_dump.sql`: file bị đổi xuống dòng sang CRLF. `demo_dump.sql` phải giữ LF
  (đã khai báo `-text` trong `.gitattributes`). Đừng mở rồi lưu lại bằng editor tự đổi xuống dòng.
- `extension "btree_gist" is not available`: cài thiếu gói contrib của PostgreSQL, cài lại bản đầy đủ.
- `permission denied` khi nạp: dùng user `postgres` (hoặc user có quyền tạo extension).

## Quy tắc khi sửa schema (dành cho người viết code)

1. **Không sửa** `schema_v3.sql` hay các migration cũ. Tạo file mới `v3_NN_ten_ngan.sql` (số tiếp theo), có `BEGIN; ... COMMIT;`,
   dùng `IF NOT EXISTS` / `IF EXISTS` để chạy lại được, ghi rõ ở đầu file nó làm gì và có hoàn tác được không.
2. Thêm file vào danh sách thứ tự ở trên và thêm một dòng kiểm tra vào `check_migrations.sql`.
3. Dựng lại `demo_dump.sql` cho khớp (từ DB trống đã chạy đủ schema, migration và seed):

   ```bash
   psql -U postgres -c "CREATE DATABASE scms_dump"
   # chạy schema_v3.sql, các v3_xx theo thứ tự, rồi demo_seed_small.sql vào scms_dump
   pg_dump -U postgres --no-owner --no-privileges -f database/demo_dump.sql scms_dump
   # xóa 2 dòng \restrict và \unrestrict, đổi CRLF thành LF nếu pg_dump chạy trên Windows
   ```
4. Ghi trong mô tả PR là có migration mới để cả nhóm biết chạy sau khi pull.

## Dành cho Claude/AI hỗ trợ thành viên nhóm

- Khi user nói "lỗi DB sau khi pull": chạy mục "Sau khi `git pull`" ở trên, theo đúng thứ tự, báo kết quả từng bước. Đừng sửa schema bằng tay.
- Chỉ chạy `demo_seed_small.sql` hoặc dựng lại DB khi user đồng ý rõ ràng, vì hai việc này xóa dữ liệu.
- Không đổi mật khẩu của tài khoản có sẵn. Mật khẩu test mặc định là `12345678`.
