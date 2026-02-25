"use client";

import { useState, useEffect } from "react";
import Link from "next/link";

export default function AdminPage() {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [mounted, setMounted] = useState(false);
  const [metrics, setMetrics] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  const ADMIN_TOKEN = process.env.NEXT_PUBLIC_ADMIN_TOKEN;
  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

  useEffect(() => {
    setMounted(true);
    const savedToken = localStorage.getItem("admin_session");
    if (savedToken === ADMIN_TOKEN) {
      setIsAuthenticated(true);
      fetchMetrics();
    }
  }, [ADMIN_TOKEN]);

  const fetchMetrics = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${apiBaseUrl}/admin/metrics`, {
        headers: { "Authorization": ADMIN_TOKEN || "" }
      });
      if (res.ok) {
        const data = await res.json();
        setMetrics(data);
      }
    } catch (err) {
      console.error("Failed to fetch metrics:", err);
    } finally {
      setLoading(false);
    }
  };

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();
    if (password === ADMIN_TOKEN) {
      localStorage.setItem("admin_session", ADMIN_TOKEN);
      setIsAuthenticated(true);
      setError("");
      fetchMetrics();
    } else {
      setError("비밀번호가 일치하지 않습니다.");
    }
  };

  const handleLogout = () => {
    localStorage.removeItem("admin_session");
    setIsAuthenticated(false);
    setMetrics(null);
  };

  if (!mounted) return null;

  if (!isAuthenticated) {
    // ... (로그인 폼 - 기존과 동일)
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4 font-sans">
        <div className="max-w-md w-full bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
          <div className="text-center mb-8">
            <h1 className="text-3xl font-black text-brand-navy mb-2">Admin Access</h1>
            <p className="text-slate-500 text-sm">관리자 비밀번호를 입력하세요.</p>
          </div>
          <form onSubmit={handleLogin} className="space-y-4">
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Password"
              className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-brand-orange outline-none transition-all"
              autoFocus
            />
            {error && <p className="text-red-500 text-xs font-bold">{error}</p>}
            <button
              type="submit"
              className="w-full bg-brand-navy text-white font-bold py-3 rounded-xl hover:bg-slate-800 transition-all shadow-lg"
            >
              로그인
            </button>
          </form>
          <div className="mt-6 text-center">
            <Link href="/" className="text-slate-400 text-xs hover:underline">홈으로 돌아가기</Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 p-6 md:p-12 font-sans text-brand-gray">
      <div className="max-w-6xl mx-auto">
        <header className="flex justify-between items-center mb-12">
          <div>
            <h1 className="text-4xl font-black text-brand-navy flex items-center gap-3">
              <span className="bg-brand-navy text-white p-2 rounded-xl text-2xl">🛡️</span>
              Admin Dashboard
            </h1>
            <p className="text-slate-500 mt-2 font-medium">서비스 통합 모니터링 시스템</p>
          </div>
          <div className="flex gap-4">
            <button
              onClick={fetchMetrics}
              disabled={loading}
              className="bg-white border border-slate-200 text-brand-orange p-2 rounded-lg hover:bg-orange-50 transition-all shadow-sm"
              title="Refresh Data"
            >
              <svg className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
            </button>
            <button
              onClick={handleLogout}
              className="bg-white border border-slate-200 text-slate-400 px-4 py-2 rounded-lg text-sm font-bold hover:bg-red-50 hover:text-red-500 transition-all shadow-sm"
            >
              로그아웃
            </button>
          </div>
        </header>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 mb-8">
          <div className="bg-white p-6 rounded-3xl shadow-sm border border-slate-100">
            <span className="text-xs font-black text-slate-400 uppercase tracking-widest block mb-2">오늘의 통계 확인</span>
            <div className="text-3xl font-black text-brand-navy">{metrics?.statsViewCount || 0}회</div>
          </div>
          <div className="bg-white p-6 rounded-3xl shadow-sm border border-slate-100">
            <span className="text-xs font-black text-slate-400 uppercase tracking-widest block mb-2">유해 URL 탐지 (24h)</span>
            <div className="text-3xl font-black text-red-500">{metrics?.maliciousCount || 0}건</div>
          </div>
          <div className="bg-white p-6 rounded-3xl shadow-sm border border-slate-100">
            <span className="text-xs font-black text-slate-400 uppercase tracking-widest block mb-2">평균 지연 시간 (Slow reqs)</span>
            <div className="text-3xl font-black text-brand-orange">
              {metrics?.slowRequests?.length > 0 
                ? (metrics.slowRequests.reduce((acc: number, cur: any) => acc + cur.duration, 0) / metrics.slowRequests.length).toFixed(0)
                : 0}ms
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          <section className="bg-white p-8 rounded-3xl shadow-sm border border-slate-100">
            <h2 className="text-xl font-black text-brand-navy mb-6 flex items-center gap-2">
              <span className="text-red-500">🚫</span> 유해 URL 탐지 목록
            </h2>
            <div className="space-y-4">
              {metrics?.maliciousList?.length > 0 ? (
                metrics.maliciousList.map((log: any, i: number) => (
                  <div key={i} className="p-4 bg-slate-50 rounded-xl border border-slate-100">
                    <div className="flex justify-between items-start mb-1">
                      <span className="text-[10px] font-black bg-red-100 text-red-600 px-2 py-0.5 rounded uppercase">
                        {log.reason}
                      </span>
                      <span className="text-[10px] text-slate-400 font-mono">
                        {new Date(log.timestamp).toLocaleString()}
                      </span>
                    </div>
                    <p className="text-xs font-bold text-brand-navy truncate">{log.url}</p>
                  </div>
                ))
              ) : (
                <p className="text-slate-400 text-sm italic py-10 text-center">탐지된 내역이 없습니다.</p>
              )}
            </div>
          </section>

          <section className="bg-white p-8 rounded-3xl shadow-sm border border-slate-100">
            <h2 className="text-xl font-black text-brand-navy mb-6 flex items-center gap-2">
              <span className="text-brand-orange">⚡</span> 성능 하위 20% (Slow Requests)
            </h2>
            <div className="space-y-4">
              {metrics?.slowRequests?.length > 0 ? (
                metrics.slowRequests.map((req: any, i: number) => (
                  <div key={i} className="p-4 bg-slate-50 rounded-xl border border-slate-100">
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-[10px] font-black text-brand-navy uppercase">{req.path}</span>
                      <span className="text-xs font-black text-brand-orange">{req.duration}ms</span>
                    </div>
                    <p className="text-[10px] text-slate-400 truncate">{req.url}</p>
                  </div>
                ))
              ) : (
                <p className="text-slate-400 text-sm italic py-10 text-center">로그 데이터가 부족합니다.</p>
              )}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
