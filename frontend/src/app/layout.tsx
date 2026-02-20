import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import Header from "@/components/Header";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "LessURL - 쉽고 빠른 지능형 URL 단축 서비스",
  description: "LessURL은 AWS 서버리스 기반의 스마트 URL 단축 서비스입니다. AI 기반 유해 사이트 분석과 정밀한 클릭 통계 기능을 제공합니다. (Simple & Powerful URL Shortener)",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <Header />
        {children}
      </body>
    </html>
  );
}
