<?php
/**
 * Calling Card IVR - International Calling Card
 *
 * Flow:
 * 1. User calls in
 * 2. System says "Enter phone number"
 * 3. User enters 9-10 digits
 * 4. System immediately dials the number
 *
 * IPSales PBX Extension Webhook
 */

header('Content-Type: application/json; charset=utf-8');

// PBX parameters
$phone     = $_GET['PBXphone'] ?? '';
$callId    = $_GET['PBXcallId'] ?? '';
$status    = $_GET['PBXcallStatus'] ?? '';

// Handle hangup
if ($status === 'HANGUP') {
    exit;
}

// Log call
$logFile = __DIR__ . '/calling-card-log.json';
$log = json_decode(@file_get_contents($logFile), true) ?: [];

// Step 1: Collect phone number (9-10 digits)
if (!isset($_GET['dial_number'])) {

    // Log incoming call
    $log[] = [
        'time'   => date('Y-m-d H:i:s'),
        'callId' => $callId,
        'caller' => $phone,
        'step'   => 'start'
    ];
    // Keep last 500 entries
    if (count($log) > 500) $log = array_slice($log, -500);
    file_put_contents($logFile, json_encode($log, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), LOCK_EX);

    echo json_encode([
        "type"        => "getDTMF",
        "name"        => "dial_number",
        "min"         => 9,
        "max"         => 10,
        "timeout"     => 15,
        "confirmType" => "text",
        "setMusic"    => "yes",
        "files"       => [
            ["text" => "הקישו את מספר הטלפון לחיוג"]
        ]
    ]);
    exit;
}

// Step 2: Dial the entered number immediately
$dialNumber = preg_replace('/[^0-9]/', '', $_GET['dial_number']);

// Validate: must be 9-10 digits
if (strlen($dialNumber) < 9 || strlen($dialNumber) > 10) {
    // Invalid number - play error and hang up
    echo json_encode([
        [
            "type"  => "simpleMenu",
            "name"  => "error",
            "times" => 1,
            "timeout" => 1,
            "enabledKeys" => "",
            "files" => [
                ["text" => "מספר לא תקין. נסו שנית"]
            ]
        ],
        ["type" => "goTo", "goTo" => ""]
    ]);
    exit;
}

// Format: add 0 prefix if 9 digits (Israeli mobile without leading 0)
$formattedNumber = $dialNumber;
if (strlen($dialNumber) === 9 && !str_starts_with($dialNumber, '0')) {
    $formattedNumber = '0' . $dialNumber;
}

// Log the dial
$log[] = [
    'time'       => date('Y-m-d H:i:s'),
    'callId'     => $callId,
    'caller'     => $phone,
    'dialNumber' => $formattedNumber,
    'step'       => 'dial'
];
if (count($log) > 500) $log = array_slice($log, -500);
file_put_contents($logFile, json_encode($log, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT), LOCK_EX);

// Dial immediately - no confirmation
echo json_encode([
    "type"         => "simpleRouting",
    "name"         => "dialCard",
    "dialPhone"    => $formattedNumber,
    "displayNumber"=> $formattedNumber,
    "routingMusic" => "yes",
    "ringSec"      => 60,
    "limit"        => ""
]);
