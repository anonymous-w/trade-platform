package jp.co.tradeplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SeedData implements ApplicationRunner {
  private final JdbcTemplate jdbc; private final PasswordEncoder encoder;
  @Value("${app.admin-user}") String username;
  @Value("${app.admin-password}") String password;
  @Value("${app.demo-data}") boolean demo;
  public SeedData(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc=jdbc; this.encoder=encoder; }
  @Override public void run(ApplicationArguments args) {
    Integer count=jdbc.queryForObject("SELECT count(*) FROM m_user",Integer.class);
    if (count != null && count==0) {
      if(password==null || password.length()<12) throw new IllegalStateException("Set ADMIN_PASSWORD (at least 12 characters) before starting an empty database");
      jdbc.update("INSERT INTO m_user(user_id,user_name,password_hash,role_code) VALUES(?,?,?,'ADMIN')", username,"管理者",encoder.encode(password));
    }
    if(demo) demoData();
  }
  private void demoData() {
    Integer n=jdbc.queryForObject("select count(*) from t_export where export_no='DEMO-EXP-001'",Integer.class);
    if(n!=null && n>0) return;
    Long customer=jdbc.queryForObject("INSERT INTO m_customer(customer_code,customer_name,country_code,address1) VALUES('DEMO-CUST','サンプル取引先','JP','DEMO ADDRESS') RETURNING customer_id",Long.class);
    Long product=jdbc.queryForObject("INSERT INTO m_product(product_code,product_name,model_no,manufacturer,unit_code) VALUES('DEMO-PROD','中古プロファイル研削盤','SPG-X','WAIDA','SET') RETURNING product_id",Long.class);
    Long exp=jdbc.queryForObject("INSERT INTO t_export(export_no,customer_id,dest_country_code,destination_name,shipper_name,export_date,export_status,ship_from,ship_to,etd_date,eta_date,transport_mode) VALUES('DEMO-EXP-001',?,'CN','サンプル納入先','DEMO SHIPPER',DATE '2025-08-06','EXPORTED','YOKOHAMA','QINGDAO',DATE '2025-08-22',DATE '2025-08-29','SEA') RETURNING export_id",Long.class,customer);
    Long item=jdbc.queryForObject("INSERT INTO t_export_item(export_id,line_no,product_id,equipment_name,model_no,quantity) VALUES(?,1,?,'中古プロファイル研削盤','SPG-X',1) RETURNING export_item_id",Long.class,exp,product);
    Long inv=jdbc.queryForObject("INSERT INTO t_invoice(export_id,invoice_no,invoice_date,currency_code,total_amount,total_quantity,total_unit) VALUES(?,'DEMO-INV-001',DATE '2025-08-06','JPY',31000000,1,'SET') RETURNING invoice_id",Long.class,exp);
    Long pl=jdbc.queryForObject("INSERT INTO t_packing_list_header(export_id,invoice_id,document_date,package_count_reported,total_gross_weight_kg_reported,total_volume_m3_reported) VALUES(?,?,DATE '2025-08-06',4,5110,9.266) RETURNING packing_list_id",Long.class,exp,inv);
    String[] parts={"本体","オイルコン","ミスト","カバー"};
    int[][] dims={{2400,1700,2100},{400,700,950},{500,700,950},{900,1100,100}};
    double[] weights={4500,300,300,10}, volumes={8.568,0.266,0.333,0.099};
    for(int i=0;i<parts.length;i++) jdbc.update("INSERT INTO t_packing_list(export_id,packing_list_id,export_item_id,line_no,part_name,part_code,pcs,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3) VALUES(?,?,?,?,?,?,1,?,?,?,?,?)",exp,pl,item,i+1,parts[i],"A"+(i+1),dims[i][0],dims[i][1],dims[i][2],weights[i],volumes[i]);
    jdbc.update("INSERT INTO t_export_container(export_id,container_type,record_basis,container_qty,source_text) VALUES(?,'20DRY','DOCUMENTED',1,'DEMO packing list only; NOT verified shipment')",exp);
  }
}
