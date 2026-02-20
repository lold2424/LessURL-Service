"use client";

import { useState, FormEvent, useEffect, useCallback } from "react";

interface PublicUrl {
  shortId: string;
  originalUrl: string;
  createdAt: string;
  clickCount: string;
  title?: string;
}

const translations = {
  ko: {
    subtitle: "쉽고 빠르고 안전한 지능형 URL 단축 서비스",
    createTitle: "단축 URL 생성",
    originalUrlLabel: "원본 URL",
    titleLabel: "제목 (선택 사항)",
    visibilityLabel: "공개 여부 설정",
    visibilityDesc: "공개 대시보드에 이 URL을 노출합니다",
    shortenBtn: "지금 줄이기",
    processing: "처리 중...",
    successTitle: "성공! 단축 URL:",
    copyBtn: "복사",
    statsTitle: "URL 통계 확인하기",
    statsDesc: "단축 ID를 입력하여 상세 클릭 분석과 AI 인사이트를 확인하세요.",
    statsPlaceholder: "단축 ID 입력 (예: ab86c836)",
    viewStatsBtn: "통계 보기",
    dashboardTitle: "공개 대시보드",
    loadingLinks: "최근 링크를 불러오는 중...",
    noLinks: "공개된 링크가 없습니다.",
    createOne: "첫 번째 링크를 만들어보세요!",
    visitOriginal: "원본 방문",
    clicks: "클릭",
    created: "생성일",
    copyAlert: "복사되었습니다!",
  },
  en: {
    subtitle: "Simple, Powerful, and Secure URL Shortener",
    createTitle: "Create Short URL",
    originalUrlLabel: "Original URL",
    titleLabel: "Title (Optional)",
    visibilityLabel: "Public Visibility",
    visibilityDesc: "Show this URL in the public dashboard",
    shortenBtn: "Shorten Now",
    processing: "Processing...",
    successTitle: "Success! Your Short URL:",
    copyBtn: "Copy",
    statsTitle: "Check Your URL Statistics",
    statsDesc: "Enter your short ID to see detailed click analysis and AI insights.",
    statsPlaceholder: "Enter short ID (e.g. ab86c836)",
    viewStatsBtn: "View Stats",
    dashboardTitle: "Public Dashboard",
    loadingLinks: "Loading recent links...",
    noLinks: "No public links found.",
    createOne: "Create one to see it here!",
    visitOriginal: "Visit Original",
    clicks: "Clicks",
    created: "Created",
    copyAlert: "Copied to clipboard!",
  }
};

export default function Home() {
  const [lang, setLang] = useState<"ko" | "en">("ko");
  const t = translations[lang];

  const [originalUrl, setOriginalUrl] = useState<string>("");
  const [title, setTitle] = useState<string>("");
  const [visibility, setVisibility] = useState<"PUBLIC" | "PRIVATE">("PRIVATE");
  const [shortenedUrl, setShortenedUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [searchId, setSearchId] = useState<string>("");
  const [publicUrls, setPublicUrls] = useState<PublicUrl[]>([]);
  const [dashboardLoading, setDashboardLoading] = useState<boolean>(false);
  const [mounted, setMounted] = useState(false);

  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

  useEffect(() => {
    const savedLang = localStorage.getItem("lang") as "ko" | "en";
    if (savedLang) setLang(savedLang);
    setMounted(true);
  }, []);

  const fetchPublicUrls = useCallback(async () => {
    if (!apiBaseUrl) return;
    setDashboardLoading(true);
    try {
      const response = await fetch(`${apiBaseUrl}/public-urls`);
      if (response.ok) {
        const data = await response.json();
        setPublicUrls(data);
      }
    } catch (err) {
      console.error("Failed to fetch public URLs:", err);
    } finally {
      setTimeout(() => setDashboardLoading(false), 500);
    }
  }, [apiBaseUrl]);

  useEffect(() => {
    if (mounted) fetchPublicUrls();
  }, [mounted, fetchPublicUrls]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setShortenedUrl(null);
    setError(null);

    try {
      if (!apiBaseUrl) throw new Error("API base URL not defined.");
      const response = await fetch(`${apiBaseUrl}/shorten`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url: originalUrl, title: title || undefined, visibility: visibility }),
      });
      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || "Failed to shorten URL");
      }
      const data = await response.json();
      setShortenedUrl(data.shortUrl);
      setOriginalUrl("");
      setTitle("");
      if (visibility === "PUBLIC") fetchPublicUrls();
    } catch (err: any) {
      setError(err.message || "An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (url: string) => {
    navigator.clipboard.writeText(url);
    alert(t.copyAlert);
  };

  const formatDate = (dateString: string) => {
    if (!mounted) return "";
    try {
      return new Date(dateString).toLocaleDateString(lang === 'ko' ? 'ko-KR' : 'en-US');
    } catch { return dateString; }
  };

  if (!mounted) return null;

  return (
    <div className="min-h-screen bg-brand-white flex flex-col items-center py-12 px-4 font-sans text-brand-gray">
      
      <div className="text-center mb-12">
        <h1 className="text-5xl font-black text-brand-navy mb-4 tracking-tight drop-shadow-sm italic">LessURL</h1>
        <p className="text-slate-500 text-lg font-medium">{t.subtitle}</p>
      </div>

      <div className="w-full max-w-5xl grid grid-cols-1 lg:grid-cols-2 gap-12 items-start">
        
        <section className="bg-white p-8 rounded-2xl shadow-xl shadow-slate-200/50 border border-slate-100 h-full">
          <h2 className="text-2xl font-bold mb-6 flex items-center gap-2">
            <span className="bg-orange-50 text-brand-orange p-2 rounded-lg">🔗</span>
            {t.createTitle}
          </h2>
          
          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-brand-gray text-sm font-bold mb-2">
                {t.originalUrlLabel} <span className="text-red-500">*</span>
              </label>
              <input
                type="url"
                value={originalUrl}
                onChange={(e) => setOriginalUrl(e.target.value)}
                placeholder="https://example.com/very/long/url"
                required
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-brand-orange focus:border-transparent outline-none transition-all"
              />
            </div>

            <div>
              <label className="block text-brand-gray text-sm font-bold mb-2">{t.titleLabel}</label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="My Awesome Website"
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-brand-orange focus:border-transparent outline-none transition-all"
              />
            </div>

            <div className="flex items-center justify-between bg-slate-50 p-4 rounded-xl border border-slate-100">
              <div>
                <span className="block text-brand-navy font-bold">{t.visibilityLabel}</span>
                <span className="text-xs text-slate-500">{t.visibilityDesc}</span>
              </div>
              <button
                type="button"
                onClick={() => setVisibility(v => v === "PUBLIC" ? "PRIVATE" : "PUBLIC")}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none ${visibility === "PUBLIC" ? "bg-brand-orange" : "bg-slate-300"}`}
              >
                <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${visibility === "PUBLIC" ? "translate-x-6" : "translate-x-1"}`} />
              </button>
            </div>

            <button type="submit" disabled={loading} className="w-full bg-brand-orange hover:bg-brand-amber text-white font-black py-4 rounded-xl shadow-lg shadow-orange-100 transition-all transform active:scale-[0.98] disabled:opacity-50">
              {loading ? t.processing : t.shortenBtn}
            </button>
          </form>

          {shortenedUrl && (
            <div className="mt-8 p-5 bg-orange-50 rounded-xl border border-orange-100 flex items-center justify-between animate-in fade-in slide-in-from-top-4">
              <div className="overflow-hidden">
                <span className="text-xs font-bold text-brand-orange uppercase tracking-wider block mb-1">{t.successTitle}</span>
                <p className="font-mono text-brand-navy truncate font-bold">{shortenedUrl}</p>
              </div>
              <button onClick={() => copyToClipboard(shortenedUrl)} className="ml-4 bg-white hover:bg-orange-50 text-brand-orange font-bold py-2 px-4 rounded-lg border border-orange-200 shadow-sm transition-all whitespace-nowrap">
                {t.copyBtn}
              </button>
            </div>
          )}

          {error && <div className="mt-6 p-4 bg-red-50 text-red-600 rounded-xl border border-red-100 text-sm font-medium">⚠️ {error}</div>}
        </section>

        <section className="bg-white p-8 rounded-2xl shadow-xl shadow-slate-200/50 border border-slate-100 flex flex-col h-full max-h-[700px]">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-2xl font-bold flex items-center gap-2">
              <span className="bg-emerald-50 text-emerald-600 p-2 rounded-lg">📊</span>
              {t.dashboardTitle}
            </h2>
            <button 
              onClick={fetchPublicUrls} 
              className={`p-2 rounded-lg transition-all ${dashboardLoading ? 'bg-slate-100 text-slate-400 cursor-not-allowed' : 'hover:bg-orange-50 text-slate-400 hover:text-brand-orange'}`}
              disabled={dashboardLoading}
              title="Refresh"
            >
              <svg 
                className={`w-5 h-5 ${dashboardLoading ? 'animate-spin' : ''}`} 
                fill="none" stroke="currentColor" viewBox="0 0 24 24"
              >
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
            </button>
          </div>

          <div className="flex-1 overflow-y-auto pr-2 space-y-4">
            {dashboardLoading ? (
              <div className="flex flex-col items-center justify-center py-32 text-slate-400">
                <div className="relative w-16 h-16 mb-6">
                  <div className="absolute inset-0 border-4 border-orange-100 rounded-full"></div>
                  <div className="absolute inset-0 border-4 border-brand-orange rounded-full border-t-transparent animate-spin"></div>
                </div>
                <p className="font-bold text-brand-navy/60 animate-pulse">{t.loadingLinks}</p>
              </div>
            ) : publicUrls.length > 0 ? (
              publicUrls.map((url) => (
                <div key={url.shortId} className="group p-4 rounded-xl border border-slate-50 hover:border-orange-200 hover:bg-orange-50/30 transition-all">
                  <div className="flex justify-between items-start mb-2">
                    <h3 className="font-bold text-brand-navy truncate pr-4">{url.title || "Untitled Link"}</h3>
                    <span className="bg-brand-orange/10 px-3 py-1.5 rounded-lg text-xs font-black text-brand-orange border border-brand-orange/20 shadow-sm whitespace-nowrap">
                      {url.clickCount} {t.clicks}
                    </span>
                  </div>
                  <div className="mb-3">
                    <a 
                      href={`${apiBaseUrl}/${url.shortId}`} 
                      target="_blank" 
                      rel="noopener noreferrer" 
                      className="text-sm text-brand-orange font-bold font-mono hover:underline decoration-orange-300 transition-all"
                    >
                      {url.shortId}
                    </a>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-[11px] text-slate-400 font-medium italic">{t.created}: {formatDate(url.createdAt)}</span>
                    <a 
                      href={`${apiBaseUrl}/${url.shortId}`} 
                      target="_blank" 
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-brand-orange/30 text-[11px] font-black text-brand-orange hover:bg-brand-orange hover:text-white transition-all shadow-sm active:scale-95 group/btn"
                    >
                      <span>{t.visitOriginal}</span>
                      <svg className="w-3 h-3 transition-transform group-hover/btn:translate-x-0.5 group-hover/btn:-translate-y-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                      </svg>
                    </a>
                  </div>
                </div>
              ))
            ) : (
              <div className="flex flex-col items-center justify-center py-20 text-slate-400 text-center">
                <p className="italic">{t.noLinks}</p>
                <p className="text-xs">{t.createOne}</p>
              </div>
            )}
          </div>
        </section>

        <section className="bg-white p-8 rounded-2xl shadow-xl shadow-slate-200/50 border border-slate-100 lg:col-span-2">
          <div className="flex flex-col md:flex-row items-center justify-between gap-6">
            <div className="flex items-center gap-4">
              <div className="bg-orange-50 text-brand-orange p-3 rounded-2xl text-2xl">📈</div>
              <div>
                <h2 className="text-xl font-bold text-brand-navy">{t.statsTitle}</h2>
                <p className="text-sm text-slate-500 text-balance">{t.statsDesc}</p>
              </div>
            </div>
            <div className="flex w-full md:w-auto gap-2">
              <input
                type="text"
                value={searchId}
                onChange={(e) => setSearchId(e.target.value)}
                placeholder={t.statsPlaceholder}
                className="flex-1 md:w-64 px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-brand-orange focus:border-transparent outline-none transition-all"
              />
              <button
                onClick={() => searchId && (window.location.href = `/stats?id=${searchId.split('/').pop()}`)}
                className="bg-brand-navy hover:bg-brand-gray text-white font-bold py-3 px-6 rounded-xl transition-all shadow-lg shadow-brand-navy/20"
              >
                {t.viewStatsBtn}
              </button>
            </div>
          </div>
        </section>
      </div>

      <footer className="mt-16 text-slate-400 text-sm">
        <p>&copy; 2026 LessURL Service. Powered by AWS Serverless.</p>
      </footer>
    </div>
  );
}