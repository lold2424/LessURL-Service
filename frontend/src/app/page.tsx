"use client";

import { useState, FormEvent } from "react";

export default function Home() {
  const [originalUrl, setOriginalUrl] = useState<string>("");
  const [shortenedUrl, setShortenedUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setShortenedUrl(null);
    setError(null);

    try {
      // Assuming the backend is running locally via `sam local start-api`
      // and is accessible at http://localhost:8080/prod
      const response = await fetch("http://localhost:8080/prod/shorten", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ url: originalUrl }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.message || "Failed to shorten URL");
      }

      const data = await response.json();
      setShortenedUrl(data.shortUrl);
    } catch (err: any) {
      setError(err.message || "An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = () => {
    if (shortenedUrl) {
      navigator.clipboard.writeText(shortenedUrl);
      alert("Shortened URL copied to clipboard!");
    }
  };

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col items-center justify-center p-4">
      <h1 className="text-4xl font-bold text-gray-800 mb-8">LessURL Shortener</h1>

      <form
        onSubmit={handleSubmit}
        className="bg-white p-8 rounded-lg shadow-md w-full max-w-md"
      >
        <div className="mb-4">
          <label htmlFor="url-input" className="block text-gray-700 text-sm font-bold mb-2">
            Enter Long URL:
          </label>
          <input
            id="url-input"
            type="url"
            value={originalUrl}
            onChange={(e) => setOriginalUrl(e.target.value)}
            placeholder="https://example.com/very/long/url"
            required
            className="shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline"
          />
        </div>
        <button
          type="submit"
          disabled={loading}
          className="bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded focus:outline-none focus:shadow-outline w-full disabled:opacity-50"
        >
          {loading ? "Shortening..." : "Shorten URL"}
        </button>
      </form>

      {shortenedUrl && (
        <div className="mt-6 bg-green-100 border-l-4 border-green-500 text-green-700 p-4 w-full max-w-md rounded-md shadow-md flex items-center justify-between">
          <p className="break-all mr-4">{shortenedUrl}</p>
          <button
            onClick={copyToClipboard}
            className="bg-green-600 hover:bg-green-700 text-white font-bold py-1 px-3 rounded text-sm focus:outline-none focus:shadow-outline"
          >
            Copy
          </button>
        </div>
      )}

      {error && (
        <div className="mt-6 bg-red-100 border-l-4 border-red-500 text-red-700 p-4 w-full max-w-md rounded-md shadow-md">
          <p>{error}</p>
        </div>
      )}

      <p className="mt-8 text-gray-600 text-sm">
        To use this, ensure your LessURL backend is running locally (e.g., via `sam local start-api`).
      </p>
    </div>
  );
}
