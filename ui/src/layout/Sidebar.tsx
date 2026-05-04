import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="h-full bg-sidebar"
      style={{
        width: "320px",
      }}
    >
      {children}
    </div>
  );
};

export default Sidebar;
