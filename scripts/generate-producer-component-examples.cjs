const fs = require('node:fs');
const read = name => JSON.parse(fs.readFileSync(`examples/${name}`,'utf8'));
const legacy = read('yeonsik-ocr.v5.restaurant-purchase.example.json');
const soup = read('yeonsik-ocr.v5.restaurant-purchase-components.example.json');
const paid = structuredClone(soup);
const record = paid.purchase_records[0];
const paidLine = {...structuredClone(record.line_items[0]),line_key:'paid-side',description:'추가 돈까스',unit_price_amount_krw:1500,gross_amount_krw:1500,net_amount_krw:1500};
record.line_items.push(paidLine);
record.totals.subtotal_amount_krw = record.totals.grand_total_amount_krw = record.totals.paid_amount_krw = 7500;
paid.nutrition[1] = {...structuredClone(paid.nutrition[0]),client_key:'food-paid-side',menu_name:'추가 돈까스'};
paid.links[1] = {purchase_record_client_key:record.client_key,purchase_line_key:'paid-side',nutrition_client_key:'food-paid-side',component_role:'side',relation_type:'purchase_line'};
const examples = [
 ['A. Legacy 3-key main',legacy,'기본 main/purchase_line. optional key를 넣지 않는다.'],
 ['B. Main 6,000원 + 무료 국물',soup,'구매 행 1개, nutrition 2개, consumption 없음. 국물은 같은 부모의 menu_component다.'],
 ['C. 실제 별도 유료 side',paid,'실제 구매 행 2개와 총 7,500원. paid-side는 독립 purchase_line/side이며 0원 음식 행이 없다.'],
 ['D. Structured option_items + parent nutrition',read('yeonsik-ocr.v5.restaurant-purchase-options.example.json'),'옵션은 실제 부모 내부에 남는다. nutrition은 부모 line을 참조하며 옵션 문구는 음식 영양 provenance가 아니다.'],
 ['E. Identity search/select outside canonical',soup,'이 canonical을 검수한 뒤 OCR runtime에서 식당·메뉴·플랫폼을 검색/선택한다. 서버가 반환한 UUID/query/choice는 metadata/checkpoint에만 저장한다. source 문자열과 canonical은 B와 동일하며 identity를 삽입하지 않는다.']
];
const text = '# V5 complete examples\n\nStatus: INACTIVE_RUNTIME_CANDIDATE. 실제 Project 원본 대조/배포 gate 전 활성화하지 않는다. 아래는 합성 예제이며 source 선언만으로 실제 evidence를 대신하지 않는다. 모든 JSON block은 core strict parser로 decode/validate한다.\n\n' + examples.map(([title,value,note]) => `## ${title}\n\n${note}\n\n\`\`\`json\n${JSON.stringify(value,null,2)}\n\`\`\`\n`).join('\n');
fs.writeFileSync('docs/producer-candidate/07_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_EXAMPLES.md',text);
