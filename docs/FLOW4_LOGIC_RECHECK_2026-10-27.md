# Flow 4 — Rà soát logic ngày 27/10/2026

## Kết quả

Đã xác nhận và sửa hai lỗi. Trong phạm vi rà soát và các ca kiểm thử bên dưới, chưa phát hiện lỗi mới khác. Kết quả này không bảo đảm hệ thống không còn lỗi ở mọi tình huống.

### 1. Bộ lọc buổi tập của Coach

- Trước sửa: chọn **Đã lên lịch** vẫn hiển thị buổi `PROPOSED`; giá trị `status` không hợp lệ trên URL cũng bị xử lý như bộ lọc đã lên lịch.
- Sau sửa: `scheduled` chỉ lấy `SCHEDULED`, `completed` chỉ lấy `DONE`, giá trị không hợp lệ trở về `all`. Bộ lọc lớp và tìm kiếm tiếp tục áp dụng đồng thời.
- Mã frontend: `src/pages/training/CoachTraining.jsx`, `src/pages/training/trainingUtils.js`.
- Kiểm thử hồi quy: `src/pages/training/trainingUtils.test.js` — 4/4 ca qua.

### 2. Ngày đánh giá lệch múi giờ

- Trước sửa: `@PastOrPresent` trên DTO dùng ngày của Bean Validation/server, có thể từ chối ngày hợp lệ tại trung tâm lúc Việt Nam đã sang ngày mới nhưng UTC chưa sang ngày.
- Sau sửa: DTO vẫn bắt buộc ngày; `TrainingService.saveEvaluation` chịu trách nhiệm kiểm tra ngày theo clock `Asia/Ho_Chi_Minh`, gồm chặn ngày đánh giá tương lai và kiểm tra thứ tự kỳ đánh giá.
- Mã backend: `dto/requestDTO/TrainingRequest.java`.
- Kiểm thử hồi quy: `TrainingIntegrationTest.evaluationDateFollowsCenterDayInsteadOfValidatorServerDay`. Ca kiểm thử tái hiện lỗi trước sửa và qua sau sửa; kiểm tra cả việc ngày tương lai vẫn bị chặn.

## Phạm vi đã rà soát

- Quyền Coach theo lớp được phân công; Member chỉ đọc dữ liệu của chính mình.
- Điều kiện danh sách học viên, rời/hủy đăng ký, buổi bù bị từ chối và buổi bị hủy.
- Cửa sổ cho phép điểm danh, chốt điểm danh, cập nhật kết quả và kiểm soát phiên bản khi nhiều tab cùng sửa.
- Kế hoạch cá nhân/cả lớp, trạng thái nháp/công bố/lưu trữ, snapshot bài tập của kết quả cũ.
- Kỳ đánh giá, nhận xét và lịch sử tiến độ.
- Phân trang, tải lại dữ liệu, bộ lọc và xử lý lỗi phía frontend.

Giữ quy tắc nghiệp vụ hiện có: roster gồm người đang `REGISTERED` và người đã có bản ghi điểm danh. Không tự suy luận quyền tạo điểm danh lịch sử cho người đã `LEFT/CANCELED` nhưng chưa có điểm danh. Xem `FLOW4_TRAINING_ATTENDANCE.md` để biết đầy đủ quy tắc.

## Bằng chứng kiểm thử

| Kiểm tra | Kết quả |
| --- | --- |
| `TrainingServiceTest` | 34/34 qua |
| `TrainingIntegrationTest` | 35/35 qua |
| Tổng backend Flow 4 | 69/69 qua, không failure/error/skipped |
| `node --test src/pages/training/trainingUtils.test.js` | 4/4 qua |
| `npm run build` | Qua |
| `npm run lint` | Exit 0; còn 17 cảnh báo có sẵn |
| Đăng nhập Coach và Member trên backend sau khởi động lại | Qua |
| GET Coach dashboard, Coach sessions, Member training | HTTP 200 |
| Frontend `http://127.0.0.1:5173` | HTTP 200 |

Backend test dùng PostgreSQL riêng `scms_flow4_audit_20261027`; ứng dụng kiểm thử UI dùng `scms_flow4_fix_ui_20261027`. Đợt rà soát này không áp migration lên DB nhóm.

Log cục bộ: `%TEMP%/flow4-logic-timezone-before.log` (tái hiện trước sửa), `%TEMP%/flow4-logic-recheck-final.log` (sau sửa). Báo cáo Maven nằm tại `target/surefire-reports/`.

Đây là kiểm tra tập trung Flow 4; không chạy lại toàn bộ test các flow khác trong đợt này. Các thao tác UI lưu điểm danh/kết quả/kế hoạch/đánh giá và đọc lại bằng Member đã được kiểm tra ở đợt redesign trước; đợt này bổ sung kiểm tra logic, hồi quy tự động và smoke test API sau sửa.
