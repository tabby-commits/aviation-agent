import React, { useEffect, useState } from "react";
import { Spin } from "antd";
import { Navigate, useLocation } from "react-router-dom";
import { me } from "../api/api.ts";

interface RequireAuthProps {
  children: React.ReactNode;
}

/**
 * 路由守卫：挂载时查询一次登录态，未登录重定向到 /login 并携带回跳路径
 */
const RequireAuth: React.FC<RequireAuthProps> = ({ children }) => {
  const location = useLocation();
  const [status, setStatus] = useState<"checking" | "ok" | "denied">("checking");

  useEffect(() => {
    let cancelled = false;
    me()
      .then(() => {
        if (!cancelled) setStatus("ok");
      })
      .catch(() => {
        if (!cancelled) setStatus("denied");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (status === "checking") {
    return (
      <div className="h-screen flex items-center justify-center">
        <Spin size="large" />
      </div>
    );
  }

  if (status === "denied") {
    const redirect = encodeURIComponent(
      location.pathname + location.search,
    );
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }

  return <>{children}</>;
};

export default RequireAuth;
