import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "export", // Enables static HTML export
  /* config options here */
  reactCompiler: true,
};

export default nextConfig;
