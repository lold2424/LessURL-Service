"use client";

import { useEffect, useState, Suspense, useRef } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";

interface StatsData {
  clicks: number;
  stats: {
    originalUrl: string;
    title?: string;
    clicksByHour: Record<string, number>;
    clicksByDay: Record<string, number>;
    clicksByReferer: Record<string, number>;
    peakHour: number | null;
    topReferer: string | null;
    aiInsight: string;
    period: string;
  };
}

const translations = {
  ko: {
    backBtn: "← 메인화면으로 돌아가기",
    loading: "통계 분석 중...",
    errorTitle: "오류 발생",
    goHome: "홈으로 돌아가기",
    aiTitle: "AI 분석 (Gemini-2.5-Flash)",
    totalClicks: "총 클릭 수",
    peakHour: "피크 타임",
    dailyTitle: "📅 일별 클릭 수 (최근 7일)",
    noDailyData: "아직 일별 데이터가 없습니다.",
    refererTitle: "🔗 주요 유입 경로",
    noRefererData: "유입 경로 데이터가 없습니다.",
    proTipTitle: "전문가 팁 💡",
    proTipDesc: (hour: number) => `당신의 오디언스는 주로 ${hour}:00 시에 가장 활발합니다. 다음 링크는 이 시간대에 맞춰 공유해보세요!`,
    clicks: "클릭",
    initializing: "대시보드 초기화 중...",
  },
  en: {
    backBtn: "← Back to Shortener",
    loading: "Analyzing statistics...",
    errorTitle: "Error Occurred",
    goHome: "Go Back Home",
    aiTitle: "AI Analysis (Gemini-2.5-Flash)",
    totalClicks: "Total Clicks",
    peakHour: "Peak Hour",
    dailyTitle: "📅 Clicks per Day (Last 7 Days)",
    noDailyData: "No daily data yet.",
    refererTitle: "🔗 Top Referers",
    noRefererData: "No referer data.",
    proTipTitle: "Pro Tip 💡",
    proTipDesc: (hour: number) => `Most of your audience visits during ${hour}:00. Consider posting your next link around this time for maximum engagement!`,
    clicks: "clicks",
    initializing: "Initializing Dashboard...",
  }
};

function StatsContent() {
  const searchParams = useSearchParams();
  const shortId = searchParams.get("id");
  const [data, setData] = useState<StatsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  const [lang, setLang] = useState<"ko" | "en">("ko");
  const t = translations[lang];

  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

  useEffect(() => {
    const savedLang = localStorage.getItem("lang") as "ko" | "en";
    if (savedLang) setLang(savedLang);
  }, []);

  useEffect(() => {
    if (!shortId) {
      setError("No Short ID provided.");
      setLoading(false);
      return;
    }

    async function fetchStats() {
      try {
        const res = await fetch(`${apiBaseUrl}/stats/${shortId}`);
        if (!res.ok) throw new Error("Failed to fetch statistics.");
        const result = await res.json();
        setData(result);
      } catch (err: any) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    }
    fetchStats();
  }, [shortId, apiBaseUrl]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-brand-white font-sans">
        <div className="text-center">
          <div className="animate-spin text-4xl mb-4 text-brand-orange">⌛</div>
          <p className="text-slate-500 font-medium">{t.loading}</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-brand-white font-sans p-4">
        <div className="bg-white p-10 rounded-3xl shadow-xl shadow-red-100 border border-red-100 text-center max-w-md">
          <div className="text-5xl mb-6">⚠️</div>
          <h1 className="text-2xl font-bold text-brand-navy mb-2">{t.errorTitle}</h1>
          <p className="text-slate-500 mb-8">{error}</p>
          <Link href="/" className="bg-brand-navy text-white px-8 py-3 rounded-xl font-bold hover:bg-brand-gray transition-all shadow-lg shadow-brand-navy/20">
            {t.goHome}
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-brand-white py-12 px-4 font-sans text-brand-gray">
      <div className="max-w-4xl mx-auto space-y-8">
        
        {/* Header Navigation */}
        <div className="flex justify-between items-center">
          <Link href="/" className="text-brand-orange font-bold flex items-center gap-2 hover:text-brand-amber transition-colors">
            {t.backBtn}
          </Link>
          <span className="text-slate-400 text-sm font-medium">ID: {shortId}</span>
        </div>

        {/* AI Insight Card - DEEP TRUST NAVY */}
        <section className="bg-brand-navy p-8 rounded-3xl shadow-2xl text-white relative overflow-hidden border border-white/5">
          <div className="relative z-10">
            <h2 className="text-brand-orange font-bold uppercase tracking-widest text-xs mb-3 flex items-center gap-2">
              <span className="animate-pulse bg-brand-orange h-2 w-2 rounded-full"></span>
              {t.aiTitle}
            </h2>
            <p className="text-2xl md:text-3xl font-black leading-tight">
              {data.stats.aiInsight}
            </p>
          </div>
          {/* Decorative background shape */}
          <div className="absolute -right-20 -bottom-20 w-64 h-64 bg-brand-orange opacity-5 rounded-full"></div>
          <div className="absolute left-1/2 top-0 w-96 h-96 bg-white opacity-[0.02] rounded-full -translate-x-1/2 -translate-y-1/2"></div>
        </section>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          
          {/* Main Stats Card */}
          <div className="md:col-span-2 space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl shadow-slate-200/50 border border-slate-50">
              <h1 className="text-3xl font-black text-brand-navy mb-1">{data.stats.title || "Untitled Link"}</h1>
              <a href={data.stats.originalUrl} target="_blank" rel="noreferrer" className="text-brand-orange hover:text-brand-amber hover:underline break-all text-sm mb-6 block font-medium">
                {data.stats.originalUrl}
              </a>
              
              <div className="grid grid-cols-2 gap-4 mt-8">
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100 flex flex-col items-center text-center">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-2">{t.totalClicks}</span>
                  <span className="text-5xl font-black text-brand-orange drop-shadow-sm">{data.clicks}</span>
                </div>
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100 flex flex-col items-center text-center">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-2">{t.peakHour}</span>
                  <span className="text-5xl font-black text-brand-navy drop-shadow-sm">{data.stats.peakHour ?? "?"}<small className="text-xl">:00</small></span>
                </div>
              </div>
            </section>

            {/* Click Chart Replacement - ENERGIZING ORANGE */}
            <section className="bg-white p-8 rounded-3xl shadow-xl shadow-slate-200/50 border border-slate-50">
              <h3 className="text-xl font-bold text-brand-navy mb-6 flex items-center gap-2">
                {t.dailyTitle}
              </h3>
              <div className="space-y-4">
                {Object.entries(data.stats.clicksByDay).length > 0 ? (
                  Object.entries(data.stats.clicksByDay).sort().map(([day, count]) => (
                    <div key={day} className="flex items-center gap-4">
                      <span className="w-24 text-xs font-black text-slate-400 uppercase tracking-tighter">{day}</span>
                      <div className="flex-1 h-3 bg-slate-100 rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-brand-orange rounded-full shadow-inner transition-all duration-1000" 
                          style={{ width: `${Math.min(100, (count / (data.clicks || 1)) * 100)}%` }}
                        ></div>
                      </div>
                      <span className="w-8 text-right text-sm font-black text-brand-navy">{count}</span>
                    </div>
                  ))
                ) : <p className="text-slate-400 italic">{t.noDailyData}</p>}
              </div>
            </section>
          </div>

          {/* Sidebar Stats */}
          <aside className="space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl shadow-slate-200/50 border border-slate-50">
              <h3 className="text-xl font-bold text-brand-navy mb-6">{t.refererTitle}</h3>
              <div className="space-y-6">
                {Object.entries(data.stats.clicksByReferer).length > 0 ? (
                  Object.entries(data.stats.clicksByReferer)
                    .sort((a, b) => b[1] - a[1])
                    .map(([ref, count]) => (
                      <div key={ref}>
                        <div className="flex justify-between text-[11px] mb-2">
                          <span className="font-black text-brand-navy truncate w-32 uppercase tracking-tight">{ref}</span>
                          <span className="text-slate-400 font-bold">{count} {t.clicks}</span>
                        </div>
                        <div className="h-2 bg-slate-50 rounded-full overflow-hidden border border-slate-100">
                          <div 
                            className="h-full bg-brand-amber rounded-full" 
                            style={{ width: `${Math.min(100, (count / (data.clicks || 1)) * 100)}%` }}
                          ></div>
                        </div>
                      </div>
                    ))
                ) : <p className="text-slate-400 italic">{t.noRefererData}</p>}
              </div>
            </section>

            <section className="bg-orange-50/50 p-6 rounded-3xl border border-orange-100 shadow-sm relative overflow-hidden group">
              <h4 className="text-brand-orange font-black text-lg mb-2 relative z-10">{t.proTipTitle}</h4>
              <p className="text-sm text-brand-navy/80 leading-relaxed font-medium relative z-10">
                {t.proTipDesc(data.stats.peakHour || 0)}
              </p>
              <div className="absolute -right-4 -bottom-4 text-4xl opacity-10 group-hover:rotate-12 transition-transform">💡</div>
            </section>
          </aside>
        </div>
      </div>
    </div>
  );
}

export default function StatsPage() {
  const [lang, setLang] = useState<"ko" | "en">("ko");

  useEffect(() => {
    const savedLang = localStorage.getItem("lang") as "ko" | "en";
    if (savedLang) setLang(savedLang);
  }, []);

  const t = translations[lang];

  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-brand-white">
        <p className="text-brand-orange animate-pulse font-black text-xl">{t.initializing}</p>
      </div>
    }>
      <StatsContent />
    </Suspense>
  );
}