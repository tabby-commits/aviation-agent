import React, { useEffect, useState } from "react";
import { Button, Card, Form, Input, Spin, message } from "antd";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { login, me } from "../../api/api.ts";
import OrbitMark from "../OrbitMark.tsx";

interface LoginFormValues {
  username: string;
  password: string;
}

/**
 * 登录页：位于路由守卫之外；已登录访问时自动跳回工作台
 */
const LoginView: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm<LoginFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [checking, setChecking] = useState(true);
  const [loggedIn, setLoggedIn] = useState(false);

  useEffect(() => {
    let cancelled = false;
    me()
      .then(() => {
        if (!cancelled) setLoggedIn(true);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setChecking(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (checking) {
    return (
      <div className="h-screen flex items-center justify-center">
        <Spin size="large" />
      </div>
    );
  }

  if (loggedIn) {
    return <Navigate to={safeRedirect(searchParams.get("redirect"))} replace />;
  }

  const onFinish = async (values: LoginFormValues) => {
    setSubmitting(true);
    try {
      await login(values.username, values.password);
      message.success("登录成功");
      navigate(safeRedirect(searchParams.get("redirect")), { replace: true });
    } catch {
      // 失败提示（用户名或密码错误 / 账号锁定）已由 http 层统一弹出
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="h-screen workbench-shell flex items-center justify-center overflow-hidden">
      <Card className="w-[380px]" variant="borderless">
        <div className="flex flex-col items-center gap-3 mb-6 select-none">
          <OrbitMark size={56} />
          <div className="text-[20px] font-semibold tracking-tight">
            航天科技情报工作台
          </div>
          <div className="tele text-flare/80">Aerospace Intelligence Console</div>
        </div>
        <Form<LoginFormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          requiredMark={false}
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: "请输入用户名" }]}
          >
            <Input autoComplete="username" placeholder="用户名" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password autoComplete="current-password" placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            登 录
          </Button>
        </Form>
      </Card>
    </div>
  );
};

/** 仅接受站内路径，防止开放重定向 */
function safeRedirect(redirect: string | null): string {
  return redirect && redirect.startsWith("/") ? redirect : "/";
}

export default LoginView;
