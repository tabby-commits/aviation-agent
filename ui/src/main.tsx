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
        colorPrimary: "#e8590c",
        colorInfo: "#e8590c",
        colorSuccess: "#2f9e6e",
        colorWarning: "#d97706",
        colorError: "#dc2626",
        colorText: "#172233",
        colorTextSecondary: "#5c6b80",
        colorTextTertiary: "#8b99ad",
        colorBorder: "#d9e2ee",
        colorBorderSecondary: "#e8eef5",
        colorBgLayout: "#f3f6fa",
        colorBgContainer: "#ffffff",
        colorBgElevated: "#ffffff",
        borderRadius: 8,
        borderRadiusLG: 10,
        borderRadiusSM: 6,
        boxShadow: "0 14px 40px rgba(7, 13, 27, 0.14)",
        boxShadowSecondary: "0 8px 24px rgba(7, 13, 27, 0.09)",
        controlHeight: 36,
        controlHeightLG: 42,
        controlHeightSM: 30,
        fontFamily:
          '"Space Grotesk", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", sans-serif',
        fontSize: 14,
        lineHeight: 1.55,
      },
    }}
  >
    <App />
  </ConfigProvider>
);
