import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App.tsx";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: "#2563eb",
        colorInfo: "#2563eb",
        colorSuccess: "#059669",
        colorWarning: "#d97706",
        colorError: "#dc2626",
        colorText: "#172033",
        colorTextSecondary: "#64748b",
        colorTextTertiary: "#94a3b8",
        colorBorder: "#d8e0ec",
        colorBorderSecondary: "#e8edf5",
        colorBgLayout: "#f4f7fb",
        colorBgContainer: "#ffffff",
        colorBgElevated: "#ffffff",
        borderRadius: 8,
        borderRadiusLG: 10,
        borderRadiusSM: 6,
        boxShadow: "0 14px 40px rgba(15, 23, 42, 0.12)",
        boxShadowSecondary: "0 8px 24px rgba(15, 23, 42, 0.08)",
        controlHeight: 36,
        controlHeightLG: 42,
        controlHeightSM: 30,
        fontFamily:
          '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
        fontSize: 14,
        lineHeight: 1.55,
      },
    }}
  >
    <App />
  </ConfigProvider>
);
