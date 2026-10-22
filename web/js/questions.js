// 質問の定義（Java 版 QuestionCatalog と同じ内容）
export const QUESTIONS = [
  {
    id: "days", text: "何日間の旅ですか？", hint: null, multi: 0,
    options: [
      { value: "1", label: "日帰り", sub: "朝から夜まで1日" },
      { value: "2", label: "1泊2日", sub: "いちばん人気の長さ" },
      { value: "3", label: "2泊3日", sub: "郊外まで足をのばせる" },
    ],
  },
  {
    id: "who", text: "誰と行きますか？", hint: null, multi: 0,
    options: [
      { value: "solo", label: "ひとり", sub: "自分のペースで" },
      { value: "friends", label: "友だち", sub: "わいわい食べ歩き" },
      { value: "couple", label: "パートナー", sub: "景色や雰囲気も大事" },
      { value: "family", label: "家族・子ども連れ", sub: "移動はゆとりをもって" },
    ],
  },
  {
    id: "main", text: "旅でいちばん楽しみなのは？", hint: "2つまで選べます", multi: 2,
    options: [
      { value: "food", label: "食べること", sub: "ラーメン・もつ鍋・屋台" },
      { value: "history", label: "神社・歴史", sub: "太宰府や城跡" },
      { value: "nature", label: "海と自然", sub: "島・海岸・公園" },
      { value: "shop", label: "買い物・街歩き", sub: "天神・博多・カフェ" },
      { value: "art", label: "アート・博物館", sub: "美術館をゆっくり" },
    ],
  },
  {
    id: "eat", text: "これだけは食べたい！", hint: "いくつでも", multi: 5,
    options: [
      { value: "ramen", label: "豚骨ラーメン", sub: "締めにも" },
      { value: "motsu", label: "もつ鍋", sub: "夜ごはんの定番" },
      { value: "mizutaki", label: "水炊き", sub: "鶏の旨みスープ" },
      { value: "yatai", label: "屋台", sub: "中洲の川沿いで" },
      { value: "seafood", label: "海鮮・ごまさば", sub: "朝の市場も" },
    ],
  },
  {
    id: "pace", text: "旅のペースは？", hint: null, multi: 0,
    options: [
      { value: "slow", label: "のんびり", sub: "1日2〜3か所" },
      { value: "mid", label: "ほどほど", sub: "1日3〜4か所" },
      { value: "fast", label: "とことん回る", sub: "1日5か所以上" },
    ],
  },
  {
    id: "move", text: "移動手段は？", hint: null, multi: 0,
    options: [
      { value: "train", label: "電車・バス・地下鉄", sub: "市内は地下鉄が便利" },
      { value: "car", label: "レンタカー", sub: "糸島・宗像も回りやすい" },
    ],
  },
  {
    id: "night", text: "夜の過ごし方は？", hint: null, multi: 0,
    options: [
      { value: "yatai", label: "屋台をはしご", sub: "中洲・天神" },
      { value: "bar", label: "落ち着いたお店で一杯", sub: "大名・中洲" },
      { value: "view", label: "夜景を見たい", sub: "福岡タワーなど" },
      { value: "rest", label: "早めにホテルへ", sub: "翌日にそなえる" },
    ],
  },
  {
    id: "range", text: "市内中心部の外にも行きますか？", hint: null, multi: 0,
    options: [
      { value: "city", label: "中心部だけ", sub: "博多・天神エリア" },
      { value: "near", label: "近郊もOK", sub: "太宰府・糸島・海の中道" },
      { value: "far", label: "遠出も歓迎", sub: "柳川・門司港も候補に" },
    ],
  },
];

export function label(questionId, value) {
  const q = QUESTIONS.find((x) => x.id === questionId);
  return q?.options.find((o) => o.value === String(value))?.label ?? "";
}
