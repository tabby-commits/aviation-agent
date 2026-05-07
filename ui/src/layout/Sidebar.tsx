import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="h-full bg-white/90 border-r border-slate-200/80 shadow-[6px_0_24px_rgba(15,23,42,0.04)]"
      style={{
        width: "320px",
        minWidth: "320px",
      }}
    >
      {children}
    </div>
  );
};

export default Sidebar;
