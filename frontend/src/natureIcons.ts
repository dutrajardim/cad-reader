import { normalizeSearch } from './callFilters'
import type { Nature } from './types'

// Regras específicas vêm antes das gerais. A legenda usa a mesma tabela do mapa.
export const natureIcons = [
  { id: 'building-fire', symbol: '🏢🔥', label: 'Incêndio em residência, comércio ou edificação', matches: (s: string) => /\bincendio\b/.test(s) && /\b(residencia\w*|residencial|habitacao|comercio|comercial|edific\w*|predio\w*|apartamento\w*|casa\w*|industria\w*|galpao\w*)\b/.test(s) },
  { id: 'vegetation-fire', symbol: '🔥', label: 'Incêndio em vegetação, mata ou pasto', matches: (s: string) => /\bincendio\b/.test(s) && /\b(vegeta\w*|mata\w*|florest\w*|pasto\w*|pastagem|lote\w*|terreno\w*)\b/.test(s) },
  { id: 'vehicle-fire', symbol: '🚗🔥', label: 'Incêndio em veículo', matches: (s: string) => /\bincendio\b/.test(s) && /\b(veiculo\w*|automovel|carro\w*|caminhao|onibus|motocicleta\w*)\b/.test(s) },
  { id: 'fire', symbol: '🔥', label: 'Outros incêndios', matches: (s: string) => /\bincendio\b/.test(s) },
  { id: 'dog', symbol: '🐕', label: 'Cão: captura, salvamento ou vistoria', matches: (s: string) => /\b(cao|caes|cachorr\w*|canin\w*)\b/.test(s) },
  { id: 'cat', symbol: '🐈', label: 'Gato: captura, salvamento ou vistoria', matches: (s: string) => /\b(gato\w*|felin\w*)\b/.test(s) },
  { id: 'insect', symbol: '🐝', label: 'Insetos, abelhas, vespas ou marimbondos', matches: (s: string) => /\b(inseto\w*|abelha\w*|vespa\w*|marimbondo\w*|enxame\w*)\b/.test(s) },
  { id: 'tree', symbol: '🌳', label: 'Árvore: vistoria, corte ou queda', matches: (s: string) => /\b(arvore\w*|arbor\w*)\b/.test(s) },
  { id: 'snake', symbol: '🐍', label: 'Cobra ou serpente', matches: (s: string) => /\b(cobra\w*|serpente\w*|ofidio\w*)\b/.test(s) },
  { id: 'traffic', symbol: '🚗', label: 'Acidente de trânsito, colisão ou capotamento', matches: (s: string) => /\b(acidente\w*\s+(de\s+)?(transito|veicul\w*|automobil\w*)|colisao|capotamento|abalroamento|atropelamento)\b/.test(s) },
  { id: 'flood', symbol: '🌊', label: 'Inundação, alagamento ou enchente', matches: (s: string) => /\b(inundacao|alagamento|enchente\w*)\b/.test(s) },
]

export function natureIcon(nature: Nature) {
  const description = normalizeSearch(nature.description)
  return natureIcons.find(icon => icon.matches(description))
}
