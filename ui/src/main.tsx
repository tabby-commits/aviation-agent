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
        colorPrimary: "#1b3a5f",
        colorInfo: "#2c5282",
        borderRadius: 8,
      },
    }}
  >
    <App />
  </ConfigProvider>
);