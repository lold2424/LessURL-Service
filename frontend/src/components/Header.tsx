"use client";

import { useState, useEffect, useRef } from "react";
import Link from "next/link";
import Image from "next/image";

const translations = {
  ko: { langName: "한국어" },
  en: { langName: "English" }
};

export default function Header() {
  const [lang, setLang] = useState<"ko" | "en">("ko");
  const [isLangMenuOpen, setIsLangMenuOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const savedLang = localStorage.getItem("lang") as "ko" | "en";
    if (savedLang) setLang(savedLang);

    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsLangMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const changeLang = (newLang: "ko" | "en") => {
    setLang(newLang);
    localStorage.setItem("lang", newLang);
    setIsLangMenuOpen(false);
    window.location.reload();
  };

  return (
    <header className="sticky top-0 z-50 w-full bg-brand-navy border-b border-white/10 px-6 py-4 shadow-lg shadow-black/10">
      <div className="max-w-5xl mx-auto flex justify-between items-center">
        {/* Logo & Brand */}
        <Link href="/" className="flex items-center gap-2 group transition-all">
          <div className="relative w-8 h-8 group-hover:scale-110 transition-transform bg-white rounded-lg p-1">
            <Image 
              src="/logo.png" 
              alt="LessURL Logo" 
              width={24}
              height={24}
              className="object-contain"
            />
          </div>
          <span className="text-xl font-black text-white tracking-tight">LessURL</span>
        </Link>

        {/* Navigation & Language Switcher */}
        <div className="flex items-center gap-6" ref={dropdownRef}>
          <div className="relative">
            <button 
              onClick={() => setIsLangMenuOpen(!isLangMenuOpen)}
              className="bg-white/10 border border-white/20 px-4 py-2 rounded-xl text-sm font-bold text-white hover:bg-white/20 transition-all shadow-sm flex items-center gap-2"
            >
              <span>🌐 {translations[lang].langName}</span>
              <span className={`text-[10px] transition-transform duration-200 ${isLangMenuOpen ? 'rotate-180' : ''}`}>▼</span>
            </button>

            {isLangMenuOpen && (
              <div className="absolute right-0 mt-2 w-32 bg-white border border-slate-100 rounded-xl shadow-2xl z-50 overflow-hidden animate-in fade-in zoom-in-95 duration-100">
                <button 
                  onClick={() => changeLang("ko")}
                  className={`w-full text-left px-4 py-3 text-sm font-medium hover:bg-brand-orange hover:text-white transition-colors ${lang === 'ko' ? 'text-brand-orange bg-slate-50' : 'text-slate-600'}`}
                >
                  한국어
                </button>
                <button 
                  onClick={() => changeLang("en")}
                  className={`w-full text-left px-4 py-3 text-sm font-medium hover:bg-brand-orange hover:text-white transition-colors ${lang === 'en' ? 'text-brand-orange bg-slate-50' : 'text-slate-600'}`}
                >
                  English
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}