const { createClient } = require('@supabase/supabase-js');

// 테스트용으로 일부러 만든 안 좋은 코드 (서비스 롤 키 하드코딩)
const supabaseUrl = 'https://xyzcompany.supabase.co';
const supabaseKey = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.service_role_key_example...'; 
const supabase = createClient(supabaseUrl, supabaseKey);

async function getUserData() {
  // 에러 핸들링 안함 (Fail-Fast 위반)
  const { data, error } = await supabase.from('users').select('*');
  return data;
}

module.exports = { getUserData };
