"use client";

import { useEffect, useState, Suspense } from "react";
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
    backBtn: "← 단축기로 돌아가기",
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
    proTipDesc: (hour: number) => `당신의 오디언스는 주로 **${hour}:00** 시에 가장 활발합니다. 다음 링크는 이 시간대에 맞춰 공유해보세요!`,
    clicks: "클릭",
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
    proTipDesc: (hour: number) => `Most of your audience visits during **${hour}:00**. Consider posting your next link around this time for maximum engagement!`,
    clicks: "clicks",
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
      <div className="min-h-screen flex items-center justify-center bg-slate-50 font-sans">
        <div className="text-center">
          <div className="animate-spin text-4xl mb-4">⌛</div>
          <p className="text-slate-500 font-medium">{t.loading}</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-slate-50 font-sans p-4">
        <div className="bg-white p-10 rounded-3xl shadow-xl border border-red-100 text-center max-w-md">
          <div className="text-5xl mb-6">⚠️</div>
          <h1 className="text-2xl font-bold text-slate-800 mb-2">{t.errorTitle}</h1>
          <p className="text-slate-500 mb-8">{error}</p>
          <Link href="/" className="bg-blue-600 text-white px-8 py-3 rounded-xl font-bold hover:bg-blue-700 transition-all">
            {t.goHome}
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 py-12 px-4 font-sans text-slate-900">
      <div className="max-w-4xl mx-auto space-y-8">
        
        {/* Header Navigation */}
        <div className="flex justify-between items-center">
          <Link href="/" className="text-blue-600 font-bold flex items-center gap-2 hover:underline">
            {t.backBtn}
          </Link>
          <span className="text-slate-400 text-sm font-medium">ID: {shortId}</span>
        </div>

        {/* AI Insight Card */}
        <section className="bg-gradient-to-br from-indigo-600 to-blue-700 p-8 rounded-3xl shadow-2xl text-white relative overflow-hidden">
          <div className="relative z-10">
            <h2 className="text-indigo-100 font-bold uppercase tracking-widest text-xs mb-3 flex items-center gap-2">
              <span className="animate-pulse bg-indigo-400 h-2 w-2 rounded-full"></span>
              {t.aiTitle}
            </h2>
            <p className="text-2xl md:text-3xl font-bold leading-tight">
              {data.stats.aiInsight}
            </p>
          </div>
          <div className="absolute -right-20 -bottom-20 w-64 h-64 bg-white opacity-10 rounded-full"></div>
        </section>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          
          <div className="md:col-span-2 space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h1 className="text-3xl font-black mb-1">{data.stats.title || "Untitled Link"}</h1>
              <a href={data.stats.originalUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline break-all text-sm mb-6 block">
                {data.stats.originalUrl}
              </a>
              
              <div className="grid grid-cols-2 gap-4 mt-8">
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-1">{t.totalClicks}</span>
                  <span className="text-4xl font-black text-slate-800">{data.clicks}</span>
                </div>
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-1">{t.peakHour}</span>
                  <span className="text-4xl font-black text-slate-800">{data.stats.peakHour ?? "?"}<small className="text-lg">:00</small></span>
                </div>
              </div>
            </section>

            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h3 className="text-xl font-bold mb-6 flex items-center gap-2">
                {t.dailyTitle}
              </h3>
              <div className="space-y-4">
                {Object.entries(data.stats.clicksByDay).length > 0 ? (
                  Object.entries(data.stats.clicksByDay).sort().map(([day, count]) => (
                    <div key={day} className="flex items-center gap-4">
                      <span className="w-24 text-sm font-bold text-slate-500">{day}</span>
                      <div className="flex-1 h-3 bg-slate-100 rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-blue-500 rounded-full" 
                          style={{ width: `${Math.min(100, (count / (data.clicks || 1)) * 100)}%` }}
                        ></div>
                      </div>
                      <span className="w-8 text-right text-sm font-black">{count}</span>
                    </div>
                  ))
                ) : <p className="text-slate-400 italic">{t.noDailyData}</p>}
              </div>
            </section>
          </div>

          <aside className="space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h3 className="text-xl font-bold mb-6">{t.refererTitle}</h3>
              <div className="space-y-6">
                {Object.entries(data.stats.clicksByReferer).length > 0 ? (
                  Object.entries(data.stats.clicksByReferer)
                    .sort((a, b) => b[1] - a[1])
                    .map(([ref, count]) => (
                      <div key={ref}>
                        <div className="flex justify-between text-sm mb-1">
                          <span className="font-bold text-slate-700 truncate w-32">{ref}</span>
                          <span className="text-slate-400">{count} {t.clicks}</span>
                        </div>
                        <div className="h-1.5 bg-slate-100 rounded-full">
                          <div 
                            className="h-full bg-emerald-500 rounded-full" 
                            style={{ width: `${Math.min(100, (count / (data.clicks || 1)) * 100)}%` }}
                          ></div>
                        </div>
                      </div>
                    ))
                ) : <p className="text-slate-400 italic">{t.noRefererData}</p>}
              </div>
            </section>

            <section className="bg-indigo-50 p-6 rounded-3xl border border-indigo-100">
              <h4 className="text-indigo-900 font-bold mb-2">{t.proTipTitle}</h4>
              <p className="text-sm text-indigo-700 leading-relaxed">
                {t.proTipDesc(data.stats.peakHour || 0)}
              </p>
            </section>
          </aside>
        </div>
      </div>
    </div>
  );
}

export default function StatsPage() {
  return (
    <Suspense fallback={<div className="min-h-screen flex items-center justify-center bg-slate-50">Loading...</div>}>
      <StatsContent />
    </Suspense>
  );
}