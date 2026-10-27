import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="h-full console"
      style={{
        width: "320px",
        minWidth: "320px",
      }}
    >
      <div className="relative z-10 h-full">{children}</div>
    </div>
  );
};

export default Sidebar;
