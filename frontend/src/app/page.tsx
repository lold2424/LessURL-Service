"use client";

import { useState, FormEvent, useEffect, useCallback } from "react";

interface PublicUrl {
  shortId: string;
  originalUrl: string;
  createdAt: string;
  clickCount: string;
  title?: string;
}

export default function Home() {
  // --- States for Shortening ---
  const [originalUrl, setOriginalUrl] = useState<string>("");
  const [title, setTitle] = useState<string>("");
  const [visibility, setVisibility] = useState<"PUBLIC" | "PRIVATE">("PRIVATE");
  const [shortenedUrl, setShortenedUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  const [publicUrls, setPublicUrls] = useState<PublicUrl[]>([]);
  const [dashboardLoading, setDashboardLoading] = useState<boolean>(false);
  const [mounted, setMounted] = useState(false);

  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;

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
      setDashboardLoading(false);
    }
  }, [apiBaseUrl]);

  useEffect(() => {
    setMounted(true);
    fetchPublicUrls();
  }, [fetchPublicUrls]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setShortenedUrl(null);
    setError(null);

    try {
      if (!apiBaseUrl) {
        throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined.");
      }

      const response = await fetch(`${apiBaseUrl}/shorten`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          url: originalUrl,
          title: title || undefined,
          visibility: visibility
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || "Failed to shorten URL");
      }

      const data = await response.json();
      setShortenedUrl(data.shortUrl);
      
      setOriginalUrl("");
      setTitle("");
      
      if (visibility === "PUBLIC") {
        fetchPublicUrls();
      }
    } catch (err: any) {
      setError(err.message || "An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (url: string) => {
    navigator.clipboard.writeText(url);
    alert("Copied to clipboard!");
  };

  const formatDate = (dateString: string) => {
    if (!mounted) return ""; // 서버와 클라이언트 간의 날짜 형식 차이 방지
    try {
      return new Date(dateString).toLocaleDateString();
    } catch {
      return dateString;
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center py-12 px-4 font-sans text-slate-900">

      {/* Header Section */}
      <div className="text-center mb-12">
        <h1 className="text-5xl font-extrabold text-blue-600 mb-2 tracking-tight">LessURL</h1>
        <p className="text-slate-500 text-lg">Simple, Powerful, and Secure URL Shortener</p>
      </div>

      <div className="w-full max-w-5xl grid grid-cols-1 lg:grid-cols-2 gap-12 items-start">

        {/* Shortener Form Card */}
        <section className="bg-white p-8 rounded-2xl shadow-xl border border-slate-100">
          <h2 className="text-2xl font-bold mb-6 flex items-center gap-2">
            <span className="bg-blue-100 text-blue-600 p-2 rounded-lg">🔗</span>
            Create Short URL
          </h2>
          
          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-slate-700 text-sm font-semibold mb-2">Original URL*</label>
              <input
                type="url"
                value={originalUrl}
                onChange={(e) => setOriginalUrl(e.target.value)}
                placeholder="https://example.com/very/long/url"
                required
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all"
              />
            </div>

            <div>
              <label className="block text-slate-700 text-sm font-semibold mb-2">Title (Optional)</label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="My Awesome Website"
                className="w-full px-4 py-3 rounded-xl border border-slate-200 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all"
              />
            </div>

            <div className="flex items-center justify-between bg-slate-50 p-4 rounded-xl border border-slate-100">
              <div>
                <span className="block text-slate-900 font-semibold">Public Visibility</span>
                <span className="text-xs text-slate-500">Show this URL in the public dashboard</span>
              </div>
              <button
                type="button"
                onClick={() => setVisibility(v => v === "PUBLIC" ? "PRIVATE" : "PUBLIC")}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none ${visibility === "PUBLIC" ? "bg-blue-600" : "bg-slate-300"}`}
              >
                <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${visibility === "PUBLIC" ? "translate-x-6" : "translate-x-1"}`} />
              </button>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full bg-blue-600 hover:bg-blue-700 text-white font-bold py-4 rounded-xl shadow-lg shadow-blue-200 transition-all transform active:scale-[0.98] disabled:opacity-50"
            >
              {loading ? "Processing..." : "Shorten Now"}
            </button>
          </form>

          {shortenedUrl && (
            <div className="mt-8 p-5 bg-blue-50 rounded-xl border border-blue-100 flex items-center justify-between animate-in fade-in slide-in-from-top-4">
              <div className="overflow-hidden">
                <span className="text-xs font-bold text-blue-600 uppercase tracking-wider block mb-1">Success! Your Short URL:</span>
                <p className="font-mono text-blue-900 truncate">{shortenedUrl}</p>
              </div>
              <button
                onClick={() => copyToClipboard(shortenedUrl)}
                className="ml-4 bg-white hover:bg-slate-50 text-blue-600 font-bold py-2 px-4 rounded-lg border border-blue-200 shadow-sm transition-all whitespace-nowrap"
              >
                Copy
              </button>
            </div>
          )}

          {error && (
            <div className="mt-6 p-4 bg-red-50 text-red-600 rounded-xl border border-red-100 text-sm font-medium">
              ⚠️ {error}
            </div>
          )}
        </section>

        {/* Public Dashboard Card */}
        <section className="bg-white p-8 rounded-2xl shadow-xl border border-slate-100 flex flex-col h-full max-h-[700px]">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-2xl font-bold flex items-center gap-2">
              <span className="bg-emerald-100 text-emerald-600 p-2 rounded-lg">📊</span>
              Public Dashboard
            </h2>
            <button 
              onClick={fetchPublicUrls}
              className="text-slate-400 hover:text-blue-600 transition-colors"
              title="Refresh"
            >
              🔄
            </button>
          </div>

          <div className="flex-1 overflow-y-auto pr-2 space-y-4">
            {dashboardLoading ? (
              <div className="flex flex-col items-center justify-center py-20 text-slate-400">
                <div className="animate-spin mb-4 text-3xl">⌛</div>
                <p>Loading recent links...</p>
              </div>
            ) : publicUrls.length > 0 ? (
              publicUrls.map((url) => (
                <div key={url.shortId} className="group p-4 rounded-xl border border-slate-100 hover:border-blue-200 hover:bg-blue-50 transition-all">
                  <div className="flex justify-between items-start mb-2">
                    <h3 className="font-bold text-slate-800 truncate pr-4">
                      {url.title || "Untitled Link"}
                    </h3>
                    <span className="bg-white px-2 py-1 rounded text-[10px] font-bold text-slate-400 border border-slate-100 shadow-sm whitespace-nowrap">
                      {url.clickCount} Clicks
                    </span>
                  </div>
                  <p className="text-sm text-blue-600 font-mono mb-3 truncate">{url.shortId}</p>
                  <div className="flex justify-between items-center">
                    <span className="text-[11px] text-slate-400 font-medium italic">
                      Created: {formatDate(url.createdAt)}
                    </span>
                    <a 
                      href={url.originalUrl} 
                      target="_blank" 
                      rel="noopener noreferrer"
                      className="text-[11px] font-bold text-slate-500 hover:text-blue-600 underline decoration-slate-200 hover:decoration-blue-200"
                    >
                      Visit Original
                    </a>
                  </div>
                </div>
              ))
            ) : (
              <div className="flex flex-col items-center justify-center py-20 text-slate-400 text-center">
                <p className="italic">No public links found.</p>
                <p className="text-xs">Create one to see it here!</p>
              </div>
            )}
          </div>
        </section>
      </div>

      <footer className="mt-16 text-slate-400 text-sm">
        <p>&copy; 2026 LessURL Service. Powered by AWS Serverless.</p>
      </footer>
    </div>
  );
}
