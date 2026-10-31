# Đưa app lên mạng để demo (Neon + Render + Vercel)

Ba dịch vụ, đều có gói miễn phí:

| Phần | Nơi chạy | Việc |
|---|---|---|
| Cơ sở dữ liệu | **Neon** | PostgreSQL, nạp file `database/demo_dump.sql` |
| Backend (repo này) | **Render** | Build từ `Dockerfile` |
| Frontend (repo `fe_swp_SportCenterV2`) | **Vercel** | Web tĩnh, chuyển tiếp `/api` sang backend |

Làm theo đúng thứ tự: DB → backend → frontend → quay lại sửa 2 biến của backend.
Chỗ nào ghi **CẦN ĐIỀN** là giá trị bạn lấy từ dịch vụ, không có sẵn trong repo.

## 1. Cơ sở dữ liệu (Neon)
1. Tạo dự án mới ở neon.tech, chọn vùng gần Việt Nam (Singapore).
2. Vào **Connection details**, bảng `Connection string` có dạng
   `postgresql://USER:MAT_KHAU@HOST/DBNAME?sslmode=require` (**CẦN ĐIỀN**, ghi lại 4 phần USER, MAT_KHAU, HOST, DBNAME).
3. Nạp dữ liệu demo từ máy bạn (cần `psql`, có sẵn khi cài PostgreSQL):

```bash
psql "postgresql://USER:MAT_KHAU@HOST/DBNAME?sslmode=require" -v ON_ERROR_STOP=1 -f database/demo_dump.sql
```

File này gồm cả cấu trúc bảng (có trigger, extension `btree_gist`) lẫn dữ liệu mẫu, **không cần chạy thêm file `v3_xx` nào**.
Mọi tài khoản demo có mật khẩu `12345678`. Ngày giờ trong dữ liệu mẫu tính từ cuối tháng 10/2026, nên các lớp và suất 1-1 sắp tới sẽ cũ dần theo thời gian.

## 2. Backend (Render)
1. Đẩy code lên GitHub, ở render.com chọn **New → Web Service**, chọn repo backend.
2. Runtime: **Docker** (Render tự thấy `Dockerfile`). Gói: **Free**.
3. Thêm các biến môi trường (Environment):

| Biến | Giá trị |
|---|---|
| `DB_URL` | `jdbc:postgresql://HOST/DBNAME?sslmode=require` (**CẦN ĐIỀN**, bắt đầu bằng `jdbc:`, không chứa user và mật khẩu) |
| `DB_USER` | USER của Neon (**CẦN ĐIỀN**) |
| `DB_PASSWORD` | MAT_KHAU của Neon (**CẦN ĐIỀN**) |
| `JWT_SECRET` | chuỗi mới, tạo bằng `openssl rand -base64 48` (không dùng lại chuỗi của máy bạn) |
| `FRONTEND_URL` | địa chỉ web Vercel, điền ở bước 4 |
| `CORS_ALLOWED_ORIGINS` | địa chỉ web Vercel, điền ở bước 4 |
| `PAYOS_RETURN_URL` | `https://TEN-BACKEND.onrender.com/api/payments/payos/return` (**CẦN ĐIỀN**, chỉ khi dùng PayOS) |
| `PAYOS_CANCEL_URL` | `https://TEN-BACKEND.onrender.com/api/payments/payos/cancel` (chỉ khi dùng PayOS) |
| `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY` | khóa **thử nghiệm** của PayOS (chỉ khi dùng PayOS) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Gmail và mật khẩu ứng dụng 16 ký tự (không có thì mã xác thực chỉ in ra log) |

4. Bấm **Create Web Service**, đợi build xong (vài phút). Lấy địa chỉ dạng `https://TEN-BACKEND.onrender.com`.
5. Thử: mở `https://TEN-BACKEND.onrender.com/api/time-slots`, thấy danh sách khung giờ là chạy.

## 3. Frontend (Vercel)
1. Trong repo FE, mở `vercel.json` và đổi `CAN-DIEN-DIA-CHI-BACKEND.onrender.com` thành địa chỉ backend ở bước 2.
2. Ở vercel.com chọn **Add New → Project**, chọn repo FE (Vercel tự nhận Vite: build `npm run build`, thư mục `dist`).
3. Deploy, lấy địa chỉ dạng `https://TEN-WEB.vercel.app`.

## 4. Quay lại backend
Sửa 2 biến `FRONTEND_URL` và `CORS_ALLOWED_ORIGINS` thành địa chỉ Vercel (không có dấu `/` ở cuối), rồi **Manual Deploy** để Render nạp lại.

## 5. Thử trên mạng
Đăng nhập bằng 4 vai trò (mật khẩu `12345678`): `manager@test.local`, `receptionist01@test.local`, `tuan.nguyen@demo.local` (coach), `lan.nguyen@demo.local` (hội viên).

## Lưu ý
- **Render gói miễn phí tự ngủ** sau khoảng 15 phút không có truy cập, lần mở đầu chậm 30 đến 60 giây. Trước giờ demo hãy mở trang sẵn.
- **Neon** tự tạm dừng khi không dùng, truy vấn đầu hơi chậm.
- Đổi khóa PayOS nếu từng dán ở chỗ công khai; không đưa khóa vào file trong repo.
- Cổng và DB đọc từ biến môi trường, chạy trên máy dev vẫn dùng giá trị mặc định cũ (`localhost:5432`), không cần đặt biến nào.
