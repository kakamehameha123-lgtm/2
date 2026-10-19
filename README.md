# Chỉ nhận chuột (MouseOnlyTouch)

App Android (Kotlin, không cần root) dành cho máy bị loạn cảm ứng: chặn mọi cú chạm của ngón tay, chỉ cho chuột (Bluetooth hoặc có dây qua OTG) điều khiển máy.

## Cách hoạt động

- Dịch vụ Trợ năng (Accessibility Service) vẽ một lớp trong suốt phủ toàn màn hình và nuốt mọi cú chạm có `TOOL_TYPE_FINGER`.
- Thao tác chuột được dịch lại thành cử chỉ rồi gửi xuống app bên dưới bằng `dispatchGesture()`.
- Chỉ bật chặn khi phát hiện có chuột đang kết nối. Rút chuột ra thì cảm ứng tự dùng lại bình thường.

| Thao tác chuột | Kết quả |
|---|---|
| Click trái | Chạm |
| Giữ chuột > 0,45 giây | Nhấn giữ |
| Kéo chuột trái | Vuốt (thực hiện **khi nhả chuột**) |
| Lăn chuột | Cuộn |
| Chuột phải | Quay lại |
| Chuột giữa | Màn hình chính |

## Build ra file APK

### Cách A: không cần cài gì, build trên GitHub
1. Tạo repo GitHub mới, upload toàn bộ thư mục này lên (nhánh `main`).
2. Vào tab **Actions** → **Build APK** → **Run workflow** (hoặc nó tự chạy sau khi push).
3. Chạy xong, tải artifact **MouseOnlyTouch-apk** về, giải nén ra `app-debug.apk`.

### Cách B: Android Studio
1. Mở thư mục này bằng Android Studio, chờ Gradle Sync.
2. Build → Build APK(s). File nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Cài và cấp quyền (Android 14, Xiaomi)

1. Cài APK. Mở app một lần.
2. **Cho phép cài đặt bị hạn chế**: Cài đặt → Ứng dụng → Chỉ nhận chuột → bấm ⋮ góc trên → *Cho phép cài đặt bị hạn chế*. Không làm bước này thì công tắc Trợ năng sẽ bị xám.
3. Cài đặt → Trợ năng → Ứng dụng đã tải xuống → **Chỉ nhận chuột** → Bật.
4. Giữ cho Xiaomi không giết dịch vụ:
   - Cài đặt → Ứng dụng → Quản lý ứng dụng → Chỉ nhận chuột → bật **Tự khởi động**.
   - Tiết kiệm pin → chọn **Không hạn chế**.
   - Mở màn hình đa nhiệm, kéo app xuống hoặc bấm giữ để **khóa** app.

## Thoát hiểm (quan trọng)

- **Nhấn Vol+ và Vol− cùng lúc**: tạm dừng / bật lại chặn cảm ứng.
- Rút hoặc tắt chuột: cảm ứng tự hoạt động lại.
- Nếu vẫn bị kẹt: khởi động vào Safe Mode (chuột vẫn dùng được) rồi gỡ app.
- Sau khi khởi động lại máy, dịch vụ chỉ chạy sau lần mở khóa đầu tiên.

## Hạn chế cần biết

- Mỗi lần bấm chuột, lớp phủ mở ra khoảng 0,1 giây để cú bấm xuyên xuống app. Trong lúc đó cảm ứng loạn có thể lọt qua.
- Vuốt từ mép màn hình (cử chỉ điều hướng của hệ thống) có thể vẫn bị cảm ứng loạn kích hoạt.
- Kéo bằng chuột chỉ được thực hiện khi nhả chuột, nên thanh trượt sẽ nhảy tới vị trí cuối thay vì chạy theo chuột.
- Chưa có thao tác hai ngón (phóng to / thu nhỏ).
