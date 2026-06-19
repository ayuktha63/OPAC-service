const express = require('express');
const { Pool } = require('pg');
const cors = require('cors');
const bodyParser = require('body-parser');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = 8082;

// Middleware
app.use(cors());
app.use(bodyParser.json());

// Database connection
const pool = new Pool({
  host: 'localhost',
  port: 5432,
  user: 'krishnaprasad',
  password: 'postgres',
  database: 'orque_opac'
});

// Licensing Keys (Secret for AES-256 symmetric encryption/decryption of licenses)
const LICENSE_SECRET = 'orque-platform-licensing-secret-key-32chars!';
const IV = crypto.randomBytes(16); // Standard IV for encryption (mock-persisted as constant for simplicity in this demo)
const KEY_IV_HEX = '1234567890abcdef1234567890abcdef'; // Static hex IV so generated keys can be decrypted by any instance

// Helper: Generate Sequential Request IDs (TEN-000001, LIC-000001)
async function generateNextId(prefix) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const res = await client.query(
      'UPDATE request_sequence_master SET current_value = current_value + 1 WHERE prefix = $1 RETURNING current_value',
      [prefix]
    );
    if (res.rows.length === 0) {
      throw new Error(`Sequence prefix ${prefix} not found`);
    }
    await client.query('COMMIT');
    const val = res.rows[0].current_value;
    return `${prefix}-${String(val).padStart(6, '0')}`;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

// Helper: Log Audit Event
async function logAudit(action, module, username, entityName, entityUuid, ipAddress, changes = []) {
  try {
    const res = await pool.query(
      `INSERT INTO audit_log (action, module, username, entity_name, entity_uuid, ip_address) 
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING uuid`,
      [action, module, username, entityName, entityUuid, ipAddress]
    );
    const auditUuid = res.rows[0].uuid;

    for (const change of changes) {
      await pool.query(
        `INSERT INTO audit_history (audit_log_uuid, field_name, old_value, new_value) 
         VALUES ($1, $2, $3, $4)`,
        [auditUuid, change.field, String(change.oldVal), String(change.newVal)]
      );
    }
  } catch (err) {
    console.error('Audit logging failed:', err);
  }
}

// Cryptography Helpers: License Encrypt & Decrypt
function encryptLicense(payload) {
  const cipher = crypto.createCipheriv(
    'aes-256-cbc',
    Buffer.from(LICENSE_SECRET),
    Buffer.from(KEY_IV_HEX, 'hex')
  );
  let encrypted = cipher.update(JSON.stringify(payload), 'utf8', 'hex');
  encrypted += cipher.final('hex');
  return encrypted;
}

function decryptLicense(encryptedKey) {
  const decipher = crypto.createDecipheriv(
    'aes-256-cbc',
    Buffer.from(LICENSE_SECRET),
    Buffer.from(KEY_IV_HEX, 'hex')
  );
  let decrypted = decipher.update(encryptedKey, 'hex', 'utf8');
  decrypted += decipher.final('utf8');
  return JSON.parse(decrypted);
}

// =========================================================================
// SCHEMA ROUTE (Dynamic JSON forms)
// =========================================================================
app.get('/api/schemas/:name', (req, res) => {
  const schemaPath = path.join(__dirname, 'schemas', `${req.params.name}.json`);
  if (fs.existsSync(schemaPath)) {
    return res.sendFile(schemaPath);
  }
  res.status(404).json({ error: 'Schema file not found' });
});

// =========================================================================
// TENANT REQUESTS API
// =========================================================================

// Get Tenant list (filterable by status tab)
app.get('/api/tenants', async (req, res) => {
  try {
    const { status } = req.query;
    let query = 'SELECT * FROM tenant_request';
    const params = [];
    if (status) {
      query += ' WHERE status = $1';
      params.push(status);
    }
    query += ' ORDER BY created_timestamp DESC';
    const result = await pool.query(query, params);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Save or Update Tenant Request (Draft)
app.post('/api/tenants', async (req, res) => {
  const { uuid, companyName, tenantName, adminUsername, adminEmail, contactNumber, country, timezone } = req.body;
  const username = req.headers['x-user'] || 'system-admin';
  const ipAddress = req.ip;

  try {
    // Check if tenant alias already in use
    const dupMaster = await pool.query('SELECT 1 FROM tenant_master WHERE tenant_name = $1', [tenantName]);
    if (dupMaster.rows.length > 0) {
      return res.status(400).json({ error: 'Tenant Alias already exists and is active.' });
    }

    if (uuid) {
      // Update
      const oldRes = await pool.query('SELECT * FROM tenant_request WHERE uuid = $1', [uuid]);
      if (oldRes.rows.length === 0) return res.status(404).json({ error: 'Request not found' });
      const oldRow = oldRes.rows[0];

      if (oldRow.status !== 'Draft' && oldRow.status !== 'Returned') {
        return res.status(400).json({ error: 'Only Draft or Returned requests can be edited.' });
      }

      await pool.query(
        `UPDATE tenant_request SET company_name=$1, tenant_name=$2, admin_username=$3, admin_email=$4, 
                contact_number=$5, country=$6, timezone=$7, updated_by=$8, updated_timestamp=CURRENT_TIMESTAMP 
         WHERE uuid=$9`,
        [companyName, tenantName, adminUsername, adminEmail, contactNumber, country, timezone, username, uuid]
      );

      const changes = [
        { field: 'companyName', oldVal: oldRow.company_name, newVal: companyName },
        { field: 'tenantName', oldVal: oldRow.tenant_name, newVal: tenantName },
        { field: 'adminUsername', oldVal: oldRow.admin_username, newVal: adminUsername }
      ];
      await logAudit('UPDATE', 'Tenant', username, 'tenant_request', uuid, ipAddress, changes);
      res.json({ uuid, success: true });
    } else {
      // Create New
      const nextId = await generateNextId('TEN');
      const insRes = await pool.query(
        `INSERT INTO tenant_request (request_id, company_name, tenant_name, status, admin_username, admin_email, contact_number, country, timezone, created_by, updated_by) 
         VALUES ($1, $2, $3, 'Draft', $4, $5, $6, $7, $8, $9, $9) RETURNING uuid`,
        [nextId, companyName, tenantName, adminUsername, adminEmail, contactNumber, country, timezone, username]
      );
      const newUuid = insRes.rows[0].uuid;
      await logAudit('INSERT', 'Tenant', username, 'tenant_request', newUuid, ipAddress);
      res.json({ uuid: newUuid, requestId: nextId, success: true });
    }
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Submit Tenant Request for Approval
app.post('/api/tenants/submit/:uuid', async (req, res) => {
  const { uuid } = req.params;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    const oldRow = await pool.query('SELECT * FROM tenant_request WHERE uuid = $1', [uuid]);
    if (oldRow.rows.length === 0) return res.status(404).json({ error: 'Request not found' });

    // Update Status
    await pool.query("UPDATE tenant_request SET status = 'In Progress' WHERE uuid = $1", [uuid]);

    // Create approval workflow record
    const appRes = await pool.query(
      `INSERT INTO approval_request (reference_uuid, trigger_event, tenant_id, status) 
       VALUES ($1, 'tenantRegistration', $2, 'Pending') RETURNING uuid`,
      [uuid, oldRow.rows[0].tenant_name]
    );

    await pool.query(
      `INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) 
       VALUES ($1, 'Submit', $2, 'Sent for approval by requester')`,
      [appRes.rows[0].uuid, username]
    );

    await logAudit('SUBMIT', 'Tenant', username, 'tenant_request', uuid, ip);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Approval / Rejection Actions
app.post('/api/tenants/:action/:uuid', async (req, res) => {
  const { action, uuid } = req.params; // approve, reject, return
  const { notes } = req.body;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    const reqRes = await pool.query('SELECT * FROM tenant_request WHERE uuid = $1', [uuid]);
    if (reqRes.rows.length === 0) return res.status(404).json({ error: 'Request not found' });
    const reqRow = reqRes.rows[0];

    const appRes = await pool.query('SELECT * FROM approval_request WHERE reference_uuid = $1 ORDER BY created_timestamp DESC LIMIT 1', [uuid]);
    if (appRes.rows.length === 0) return res.status(404).json({ error: 'Workflow not initialized' });
    const approvalUuid = appRes.rows[0].uuid;

    if (action === 'approve') {
      // 1. Update request status
      await pool.query("UPDATE tenant_request SET status = 'Active' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Approved' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Approve', $2, $3)", [approvalUuid, username, notes]);

      // 2. Create Tenant Master
      const tmRes = await pool.query(
        "INSERT INTO tenant_master (tenant_name, company_name, status, created_by) VALUES ($1, $2, 'Active', $3) RETURNING uuid",
        [reqRow.tenant_name, reqRow.company_name, username]
      );
      const tenantUuid = tmRes.rows[0].uuid;

      // 3. Create Tenant Configuration
      await pool.query(
        "INSERT INTO tenant_configuration (tenant_uuid, branding_logo_url, theme_primary_color, settings_json) VALUES ($1, '', '#4F46E5', '{}')",
        [tenantUuid]
      );

      // 4. Create default roles (Requester, Approver)
      const rReq = await pool.query("INSERT INTO role_master (tenant_uuid, role_name, is_system_default) VALUES ($1, 'Requester', true) RETURNING uuid", [tenantUuid]);
      const rApp = await pool.query("INSERT INTO role_master (tenant_uuid, role_name, is_system_default) VALUES ($1, 'Approver', true) RETURNING uuid", [tenantUuid]);

      await pool.query("INSERT INTO role_permission (role_uuid, access_policy_key) VALUES ($1, 'tenant:read'), ($1, 'tenant:write'), ($1, 'license:read')", [rReq.rows[0].uuid]);
      await pool.query("INSERT INTO role_permission (role_uuid, access_policy_key) VALUES ($1, 'tenant:approve'), ($1, 'license:approve')", [rApp.rows[0].uuid]);

      // 5. Create Admin user
      await pool.query(
        "INSERT INTO user_master (tenant_uuid, username, email, status) VALUES ($1, $2, $3, 'Active')",
        [tenantUuid, reqRow.admin_username, reqRow.admin_email]
      );

      // 6. Queue Welcome Email
      const tempPass = crypto.randomBytes(6).toString('hex'); // Generate temporary password
      const emailBody = `Welcome to Orque Platform Administration Center (OPAC)!
      
Your tenant "${reqRow.tenant_name}" is ready.
Username: ${reqRow.admin_username}
Temporary Password: ${tempPass}
OPAC URL: http://localhost:8082/opac/login

Please login and change your password.`;

      await pool.query(
        "INSERT INTO email_queue (to_email, subject, body, status) VALUES ($1, $2, $3, 'Sent')",
        [reqRow.admin_email, 'Your Orque OPAC Credentials', emailBody]
      );

      // 7. Auto-create blank Initial License Request (saved as active pending user configurations)
      const licId = await generateNextId('LIC');
      await pool.query(
        `INSERT INTO license_request (request_id, tenant_uuid, company_name, email, requested_by, status, request_type) 
         VALUES ($1, $2, $3, $4, 'System Onboarding', 'Draft', 'New')`,
        [licId, tenantUuid, reqRow.company_name, reqRow.admin_email]
      );

      // In-app notification
      await pool.query(
        "INSERT INTO notification_master (tenant_uuid, type, message) VALUES ($1, 'TenantApproved', $2)",
        [tenantUuid, `Tenant request for company ${reqRow.company_name} was approved.`]
      );

      await logAudit('APPROVE', 'Tenant', username, 'tenant_request', uuid, ip);
    } else if (action === 'reject') {
      await pool.query("UPDATE tenant_request SET status = 'Inactive' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Rejected' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Reject', $2, $3)", [approvalUuid, username, notes]);
      await logAudit('REJECT', 'Tenant', username, 'tenant_request', uuid, ip);
    } else if (action === 'return') {
      await pool.query("UPDATE tenant_request SET status = 'Returned' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Returned' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Return', $2, $3)", [approvalUuid, username, notes]);
      await logAudit('RETURN', 'Tenant', username, 'tenant_request', uuid, ip);
    }

    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Approval History
app.get('/api/tenants/history/:uuid', async (req, res) => {
  try {
    const result = await pool.query(
      `SELECT h.* FROM approval_history h
       JOIN approval_request r ON r.uuid = h.approval_request_uuid
       WHERE r.reference_uuid = $1 ORDER BY h.created_timestamp ASC`,
      [req.params.uuid]
    );
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// =========================================================================
// LICENSE LIFECYCLE API
// =========================================================================

// Get Licenses List
app.get('/api/licenses', async (req, res) => {
  try {
    const { status } = req.query;
    let query = 'SELECT * FROM license_request';
    const params = [];
    if (status) {
      query += ' WHERE status = $1';
      params.push(status);
    }
    query += ' ORDER BY created_timestamp DESC';
    const result = await pool.query(query, params);

    // Populate each license request with its product details
    const populated = [];
    for (const lic of result.rows) {
      const prodsRes = await pool.query('SELECT * FROM license_product WHERE license_request_uuid = $1', [lic.uuid]);
      const products = [];
      for (const p of prodsRes.rows) {
        const featsRes = await pool.query('SELECT feature_name FROM license_feature WHERE license_product_uuid = $1', [p.uuid]);
        products.push({
          ...p,
          features: featsRes.rows.map(f => f.feature_name)
        });
      }
      populated.push({
        ...lic,
        licenseDetails: products
      });
    }

    res.json(populated);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Save or Update License Request
app.post('/api/licenses', async (req, res) => {
  const { uuid, tenantUuid, companyName, email, requestedBy, licenseDetails } = req.body;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    let licenseRequestUuid = uuid;
    if (uuid) {
      // Update
      const oldRow = await pool.query('SELECT * FROM license_request WHERE uuid = $1', [uuid]);
      if (oldRow.rows.length === 0) return res.status(404).json({ error: 'Request not found' });
      if (oldRow.rows[0].status !== 'Draft' && oldRow.rows[0].status !== 'Returned') {
        return res.status(400).json({ error: 'Only Draft or Returned licenses can be edited.' });
      }

      await pool.query(
        `UPDATE license_request SET company_name=$1, email=$2, requested_by=$3, updated_timestamp=CURRENT_TIMESTAMP 
         WHERE uuid=$4`,
        [companyName, email, requestedBy, uuid]
      );
      // Delete old products & features
      await pool.query('DELETE FROM license_product WHERE license_request_uuid = $1', [uuid]);
    } else {
      // Insert
      const nextId = await generateNextId('LIC');
      const insRes = await pool.query(
        `INSERT INTO license_request (request_id, tenant_uuid, company_name, email, requested_by, status, request_type) 
         VALUES ($1, $2, $3, $4, $5, 'Draft', 'New') RETURNING uuid`,
        [nextId, tenantUuid || null, companyName, email, requestedBy]
      );
      licenseRequestUuid = insRes.rows[0].uuid;
    }

    // Insert Products & Features
    if (licenseDetails && Array.isArray(licenseDetails)) {
      for (const prod of licenseDetails) {
        const prodRes = await pool.query(
          `INSERT INTO license_product (license_request_uuid, product_name, start_date, end_date, user_limit, concurrent_limit) 
           VALUES ($1, $2, $3, $4, $5, $6) RETURNING uuid`,
          [licenseRequestUuid, prod.productName, prod.startDate, prod.endDate, prod.userLimit, prod.concurrentLimit]
        );
        const prodUuid = prodRes.rows[0].uuid;

        if (prod.features && Array.isArray(prod.features)) {
          for (const feat of prod.features) {
            await pool.query('INSERT INTO license_feature (license_product_uuid, feature_name) VALUES ($1, $2)', [prodUuid, feat]);
          }
        }
      }
    }

    await logAudit(uuid ? 'UPDATE' : 'INSERT', 'License', username, 'license_request', licenseRequestUuid, ip);
    res.json({ uuid: licenseRequestUuid, success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Submit License for Approval
app.post('/api/licenses/submit/:uuid', async (req, res) => {
  const { uuid } = req.params;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    const oldRow = await pool.query('SELECT * FROM license_request WHERE uuid = $1', [uuid]);
    if (oldRow.rows.length === 0) return res.status(404).json({ error: 'Request not found' });

    await pool.query("UPDATE license_request SET status = 'In Progress' WHERE uuid = $1", [uuid]);

    const appRes = await pool.query(
      `INSERT INTO approval_request (reference_uuid, trigger_event, tenant_id, status) 
       VALUES ($1, 'licenseApproval', $2, 'Pending') RETURNING uuid`,
      [uuid, oldRow.rows[0].company_name]
    );

    await pool.query(
      `INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) 
       VALUES ($1, 'Submit', $2, 'License request sent for approval')`,
      [appRes.rows[0].uuid, username]
    );

    await logAudit('SUBMIT', 'License', username, 'license_request', uuid, ip);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Approve License & Encrypt License Key
app.post('/api/licenses/:action/:uuid', async (req, res) => {
  const { action, uuid } = req.params; // approve, reject, return
  const { notes } = req.body;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    const reqRes = await pool.query('SELECT * FROM license_request WHERE uuid = $1', [uuid]);
    if (reqRes.rows.length === 0) return res.status(404).json({ error: 'Request not found' });
    const reqRow = reqRes.rows[0];

    const appRes = await pool.query('SELECT * FROM approval_request WHERE reference_uuid = $1 ORDER BY created_timestamp DESC LIMIT 1', [uuid]);
    if (appRes.rows.length === 0) return res.status(404).json({ error: 'Workflow not initialized' });
    const approvalUuid = appRes.rows[0].uuid;

    if (action === 'approve') {
      // 1. Fetch details
      const prodsRes = await pool.query('SELECT * FROM license_product WHERE license_request_uuid = $1', [uuid]);
      const products = [];
      let minStart = null;
      let maxEnd = null;

      for (const p of prodsRes.rows) {
        const featsRes = await pool.query('SELECT feature_name FROM license_feature WHERE license_product_uuid = $1', [p.uuid]);
        products.push({
          productName: p.product_name,
          startDate: p.start_date,
          endDate: p.end_date,
          userLimit: p.user_limit,
          concurrentLimit: p.concurrent_limit,
          features: featsRes.rows.map(f => f.feature_name)
        });

        const sDate = new Date(p.start_date);
        const eDate = new Date(p.end_date);
        if (!minStart || sDate < minStart) minStart = sDate;
        if (!maxEnd || eDate > maxEnd) maxEnd = eDate;
      }

      // 2. Generate JSON License Payload to Encrypt
      const licensePayload = {
        licenseVersion: '1.0',
        issueDate: new Date().toISOString().split('T')[0],
        expiryDate: maxEnd ? maxEnd.toISOString().split('T')[0] : new Date().toISOString().split('T')[0],
        licenseType: 'Standard',
        tenant: {
          tenantName: reqRow.company_name.toLowerCase().replace(/[^a-z0-9]/g, '-'),
          companyName: reqRow.company_name
        },
        products: products,
        digitalSignature: crypto.createHmac('sha256', LICENSE_SECRET).update(JSON.stringify(products)).digest('hex')
      };

      // 3. Encrypt Key
      const encryptedKey = encryptLicense(licensePayload);

      // Determine tenant uuid
      let targetTenantUuid = reqRow.tenant_uuid;
      if (!targetTenantUuid) {
        // Fallback or lookup by name
        const lookup = await pool.query('SELECT uuid FROM tenant_master WHERE company_name = $1 LIMIT 1', [reqRow.company_name]);
        if (lookup.rows.length > 0) targetTenantUuid = lookup.rows[0].uuid;
      }

      if (!targetTenantUuid) {
        return res.status(400).json({ error: 'No active tenant linked to this customer name' });
      }

      // 4. Save into license_master
      const lmRes = await pool.query(
        `INSERT INTO license_master (tenant_uuid, license_key, status, expiry_date, digital_signature) 
         VALUES ($1, $2, 'Active', $3, $4) RETURNING uuid`,
        [targetTenantUuid, encryptedKey, licensePayload.expiryDate, licensePayload.digitalSignature]
      );
      const lmUuid = lmRes.rows[0].uuid;

      // Link product table entries to the master table as well
      await pool.query('UPDATE license_product SET license_uuid = $1 WHERE license_request_uuid = $2', [lmUuid, uuid]);

      // 5. Update Statuses
      await pool.query("UPDATE license_request SET status = 'Active' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Approved' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Approve', $2, $3)", [approvalUuid, username, notes]);

      // 6. Queue Email with Encrypted License Key
      const emailBody = `Congratulations! Your Orque Product License key has been issued.

License Details:
Products: ${products.map(p => p.productName).join(', ')}
Expiry Date: ${licensePayload.expiryDate}

Please copy the encrypted license key below and apply it inside your OPAC Tenant Configuration tab:

----- BEGIN ORQUE LICENSE KEY -----
${encryptedKey}
----- END ORQUE LICENSE KEY -----`;

      await pool.query(
        "INSERT INTO email_queue (to_email, subject, body, status) VALUES ($1, $2, $3, 'Sent')",
        [reqRow.email, 'Your Orque Product License Key', emailBody]
      );

      // Update Tenant Configuration entitlements settings
      const productsMap = {};
      products.forEach(p => {
        productsMap[p.productName] = {
          enabled: true,
          expiry: p.endDate,
          features: p.features
        };
      });
      await pool.query(
        "UPDATE tenant_configuration SET settings_json = $1 WHERE tenant_uuid = $2",
        [JSON.stringify({ licensedProducts: productsMap }), targetTenantUuid]
      );

      await logAudit('APPROVE', 'License', username, 'license_request', uuid, ip);
    } else if (action === 'reject') {
      await pool.query("UPDATE license_request SET status = 'Cancelled' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Rejected' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Reject', $2, $3)", [approvalUuid, username, notes]);
      await logAudit('REJECT', 'License', username, 'license_request', uuid, ip);
    } else if (action === 'return') {
      await pool.query("UPDATE license_request SET status = 'Returned' WHERE uuid = $1", [uuid]);
      await pool.query("UPDATE approval_request SET status = 'Returned' WHERE uuid = $1", [approvalUuid]);
      await pool.query("INSERT INTO approval_history (approval_request_uuid, action, actor_username, notes) VALUES ($1, 'Return', $2, $3)", [approvalUuid, username, notes]);
      await logAudit('RETURN', 'License', username, 'license_request', uuid, ip);
    }

    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Apply License Upload Flow
app.post('/api/licenses/apply', async (req, res) => {
  const { licenseKey, tenantUuid } = req.body;
  const username = req.headers['x-user'] || 'system-admin';
  const ip = req.ip;

  try {
    if (!licenseKey) return res.status(400).json({ error: 'License key is empty.' });

    // Decrypt key
    let payload;
    try {
      payload = decryptLicense(licenseKey.trim());
    } catch (err) {
      return res.status(400).json({ error: 'Invalid license key format or corrupted encryption payload.' });
    }

    // Validate Signature
    const calculatedSig = crypto.createHmac('sha256', LICENSE_SECRET).update(JSON.stringify(payload.products)).digest('hex');
    if (calculatedSig !== payload.digitalSignature) {
      return res.status(400).json({ error: 'License digital signature is invalid. Key may be tampered.' });
    }

    // Validate Expiry
    const today = new Date().toISOString().split('T')[0];
    if (payload.expiryDate < today) {
      return res.status(400).json({ error: `License has expired on ${payload.expiryDate}.` });
    }

    // Validate Tenant association (by name alias)
    const tenantRes = await pool.query('SELECT * FROM tenant_master WHERE uuid = $1', [tenantUuid]);
    if (tenantRes.rows.length === 0) return res.status(404).json({ error: 'Tenant not found.' });
    const tenantRow = tenantRes.rows[0];

    // Activate Products
    const productsMap = {};
    payload.products.forEach(p => {
      productsMap[p.productName] = {
        enabled: true,
        expiry: p.endDate,
        features: p.features
      };
    });

    await pool.query(
      "UPDATE tenant_configuration SET settings_json = $1 WHERE tenant_uuid = $2",
      [JSON.stringify({ licensedProducts: productsMap }), tenantUuid]
    );

    // Save mapping to license_master
    await pool.query(
      `INSERT INTO license_master (tenant_uuid, license_key, status, expiry_date, digital_signature) 
       VALUES ($1, $2, 'Active', $3, $4)`,
      [tenantUuid, licenseKey, payload.expiryDate, payload.digitalSignature]
    );

    await logAudit('APPLY_LICENSE', 'License', username, 'tenant_configuration', tenantUuid, ip);
    res.json({ success: true, payload });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// =========================================================================
// SESSIONS API
// =========================================================================
app.get('/api/sessions', async (req, res) => {
  try {
    const result = await pool.query('SELECT * FROM session_master ORDER BY login_timestamp DESC');
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/sessions/create', async (req, res) => {
  const { tenantUuid, username, device, browser, ipAddress } = req.body;
  try {
    const result = await pool.query(
      `INSERT INTO session_master (tenant_uuid, username, device, browser, ip_address) 
       VALUES ($1, $2, $3, $4, $5) RETURNING uuid`,
      [tenantUuid || null, username, device, browser, ipAddress]
    );
    res.json({ uuid: result.rows[0].uuid, success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/sessions/terminate', async (req, res) => {
  const { uuid } = req.body;
  const actor = req.headers['x-user'] || 'system-admin';
  try {
    await pool.query(
      "UPDATE session_master SET logout_timestamp = CURRENT_TIMESTAMP, session_duration_seconds = EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - login_timestamp)) WHERE uuid = $1",
      [uuid]
    );
    await logAudit('FORCE_LOGOUT', 'Session', actor, 'session_master', uuid, 'localhost');
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// =========================================================================
// GENERAL APIS (Audit logs, Notifications, Tenant Master details)
// =========================================================================

// Get audit logs
app.get('/api/audit-logs', async (req, res) => {
  try {
    const result = await pool.query(`
      SELECT l.*, ARRAY_AGG(h.field_name || ': ' || COALESCE(h.old_value, 'null') || ' -> ' || h.new_value) as changes
      FROM audit_log l
      LEFT JOIN audit_history h ON h.audit_log_uuid = l.uuid
      GROUP BY l.uuid
      ORDER BY l.created_timestamp DESC
    `);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Get active tenants
app.get('/api/tenants-master', async (req, res) => {
  try {
    const result = await pool.query(`
      SELECT m.*, c.branding_logo_url, c.theme_primary_color, c.settings_json 
      FROM tenant_master m
      LEFT JOIN tenant_configuration c ON c.tenant_uuid = m.uuid
    `);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Get notification alerts
app.get('/api/notifications', async (req, res) => {
  try {
    const result = await pool.query('SELECT * FROM notification_master ORDER BY created_timestamp DESC');
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Get emails queue
app.get('/api/email-queue', async (req, res) => {
  try {
    const result = await pool.query('SELECT * FROM email_queue ORDER BY created_timestamp DESC');
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Start Server
app.listen(PORT, () => {
  console.log(`Orque Platform Administration Center backend running on port ${PORT}`);
});
