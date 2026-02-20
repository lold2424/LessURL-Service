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

function StatsContent() {
  const searchParams = useSearchParams();
  const shortId = searchParams.get("id");
  const [data, setData] = useState<StatsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

  useEffect(() => {
    if (!shortId) {
      setError("No Short ID provided.");
      setLoading(false);
      return;
    }

    async function fetchStats() {
      try {
        const res = await fetch(`${apiBaseUrl}/stats/${shortId}`);
        if (!res.ok) {
          throw new Error("Failed to fetch statistics. Please check the ID.");
        }
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
          <p className="text-slate-500 font-medium">Analyzing statistics...</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-slate-50 font-sans p-4">
        <div className="bg-white p-10 rounded-3xl shadow-xl border border-red-100 text-center max-w-md">
          <div className="text-5xl mb-6">⚠️</div>
          <h1 className="text-2xl font-bold text-slate-800 mb-2">Error Occurred</h1>
          <p className="text-slate-500 mb-8">{error}</p>
          <Link href="/" className="bg-blue-600 text-white px-8 py-3 rounded-xl font-bold hover:bg-blue-700 transition-all">
            Go Back Home
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
            ← Back to Shortener
          </Link>
          <span className="text-slate-400 text-sm font-medium">ID: {shortId}</span>
        </div>

        {/* AI Insight Card - THE STAR FEATURE */}
        <section className="bg-gradient-to-br from-indigo-600 to-blue-700 p-8 rounded-3xl shadow-2xl text-white relative overflow-hidden">
          <div className="relative z-10">
            <h2 className="text-indigo-100 font-bold uppercase tracking-widest text-xs mb-3 flex items-center gap-2">
              <span className="animate-pulse bg-indigo-400 h-2 w-2 rounded-full"></span>
              AI Analysis (Gemini-2.5-Flash)
            </h2>
            <p className="text-2xl md:text-3xl font-bold leading-tight">
              {data.stats.aiInsight}
            </p>
          </div>
          {/* Decorative background shape */}
          <div className="absolute -right-20 -bottom-20 w-64 h-64 bg-white opacity-10 rounded-full"></div>
        </section>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          
          {/* Main Stats Card */}
          <div className="md:col-span-2 space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h1 className="text-3xl font-black mb-1">{data.stats.title || "Untitled Link"}</h1>
              <a href={data.stats.originalUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline break-all text-sm mb-6 block">
                {data.stats.originalUrl}
              </a>
              
              <div className="grid grid-cols-2 gap-4 mt-8">
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-1">Total Clicks</span>
                  <span className="text-4xl font-black text-slate-800">{data.clicks}</span>
                </div>
                <div className="bg-slate-50 p-6 rounded-2xl border border-slate-100">
                  <span className="block text-slate-400 text-xs font-bold uppercase mb-1">Peak Hour</span>
                  <span className="text-4xl font-black text-slate-800">{data.stats.peakHour ?? "?"}<small className="text-lg">:00</small></span>
                </div>
              </div>
            </section>

            {/* Click Chart Replacement (Simple List) */}
            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h3 className="text-xl font-bold mb-6 flex items-center gap-2">
                📅 Clicks per Day (Last 7 Days)
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
                ) : <p className="text-slate-400 italic">No daily data yet.</p>}
              </div>
            </section>
          </div>

          {/* Sidebar Stats */}
          <aside className="space-y-8">
            <section className="bg-white p-8 rounded-3xl shadow-xl border border-slate-100">
              <h3 className="text-xl font-bold mb-6">🔗 Top Referers</h3>
              <div className="space-y-6">
                {Object.entries(data.stats.clicksByReferer).length > 0 ? (
                  Object.entries(data.stats.clicksByReferer)
                    .sort((a, b) => b[1] - a[1])
                    .map(([ref, count]) => (
                      <div key={ref}>
                        <div className="flex justify-between text-sm mb-1">
                          <span className="font-bold text-slate-700 truncate w-32">{ref}</span>
                          <span className="text-slate-400">{count} clicks</span>
                        </div>
                        <div className="h-1.5 bg-slate-100 rounded-full">
                          <div 
                            className="h-full bg-emerald-500 rounded-full" 
                            style={{ width: `${Math.min(100, (count / (data.clicks || 1)) * 100)}%` }}
                          ></div>
                        </div>
                      </div>
                    ))
                ) : <p className="text-slate-400 italic">No referer data.</p>}
              </div>
            </section>

            <section className="bg-indigo-50 p-6 rounded-3xl border border-indigo-100">
              <h4 className="text-indigo-900 font-bold mb-2">Pro Tip 💡</h4>
              <p className="text-sm text-indigo-700 leading-relaxed">
                Most of your audience visits during **{data.stats.peakHour}:00**. Consider posting your next link around this time for maximum engagement!
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
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <p className="text-slate-500 animate-pulse font-bold">Initializing Dashboard...</p>
      </div>
    }>
      <StatsContent />
    </Suspense>
  );
}